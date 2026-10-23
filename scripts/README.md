# 官方 RISC-V 测试脚本

这两个脚本将已编译的官方测试程序转换为 Memory 能读取的 HEX，再通过 ChiselSim 批量运行 CPU 测试。脚本可以从任意工作目录调用，输入和输出位置都相对于项目根目录解析。

## 1. 准备环境

需要 RISC-V GCC 工具链（含 GNU binutils）、Bash、`od`、`readelf`、`awk`，以及 JDK、sbt、Verilator 和 C++ 构建工具。将交叉工具链的 `bin/` 加入 PATH，使用其中的 RISC-V `objcopy` 转换现成 ELF；无需重新编译官方测试，也不需要 Spike。

项目已提供预编译的官方测试 ELF，脚本直接从以下目录读取，无需自行编译：

```text
src/prebuilt/share/riscv-tests/isa/
├── rv32ui-p-add          # ELF 可执行文件，脚本读取它
├── rv32ui-p-add.dump     # 反汇编文本，不作为转换输入
├── rv32ui-p-lw
└── ...
```

当前 Core 从地址 0 启动，Memory 容量为 16 KiB，退出判断是 PC 到达 `0x44`。随项目提供的 ELF 已从地址 0 链接，并采用与当前退出约定匹配的代码布局。脚本检查 ELF 类型、入口地址和生成的镜像大小，但不会自动修改程序的链接地址或退出代码。不能把按 `0x80000000` 链接的程序简单截去前面的地址就认为完成了重定位。

## 2. tohex.sh：ELF → BIN → HEX

在项目根目录执行：

```sh
./scripts/tohex.sh                  # 转换全部 32 项
./scripts/tohex.sh add beq lw sw     # 只转换指定项
./scripts/tohex.sh --list           # 查看白名单
```

工具选择顺序是 `riscv32-unknown-elf-objcopy`、`riscv64-unknown-elf-objcopy`、`riscv64-linux-gnu-objcopy`。未找到时会报错，不会自动回退到其他工具。也可以指定可执行文件：

```sh
OBJCOPY=/path/to/riscv64-unknown-elf-objcopy ./scripts/tohex.sh
```

`OBJCOPY` 是一个可执行文件名或路径，不是包含额外参数的命令串。请指向 RISC-V GCC 工具链配套的 `objcopy`；宿主机的普通 `objcopy` 不一定支持 RISC-V ELF。

每个程序的转换过程为：

```sh
riscv64-unknown-elf-objcopy -O binary input-elf output.bin
od -An -v -tx1 -w1 output.bin | awk '{print $1}' > output.hex
```

- BIN 保留可加载代码、数据和段间间隙；只提取 `.text` 会丢失 LW/SW 所需的数据。
- HEX 每行一个字节，对应 `Mem(16384, UInt(8.W))` 的一个元素。
- `od -v` 禁止把重复字节行压缩成 `*`；`-An` 去掉地址列。
- 按 BIN 的字节顺序转换，不再手动颠倒字节。例如指令 `0x00500193` 的四行是 `93`、`01`、`50`、`00`。
- 输出到 `src/riscv/rv32ui-p-指令名.bin` 和同名 `.hex`；这些生成文件由 Git 忽略，首次克隆后需先转换。重新运行会替换所选程序的这两个产物。单个程序先转换到临时目录，成功后才更新文件。

默认白名单包含 `simple` 和 31 项已实现的基础整数指令测试：

```text
simple
add addi sub and andi or ori xor xori
sll slli srl srli sra srai slt slti sltu sltiu
lui auipc
beq bne blt bge bltu bgeu
jal jalr lw sw
```

暂不包含字节/半字访存、FENCE.I、乘除法、原子、浮点、压缩指令、虚拟内存和完整特权测试。Core 虽有部分 CSR/ECALL 实现，也不能据此认为它能通过完整的 `rv32mi`/`rv32si` 测试。

## 3. riscv-tests.sh：逐个运行和汇总

先转换，再测试：

```sh
./scripts/tohex.sh
./scripts/riscv-tests.sh
```

也可以先跑少量程序：

```sh
./scripts/tohex.sh add beq lw sw
./scripts/riscv-tests.sh add beq lw sw
```

批量脚本从 `tohex.sh --list` 获取同一份白名单，不会把目录中无关的 HEX 自动纳入测试。它不自动转换镜像；输入缺失会在开始测试前报错。

每项调用相当于：

```sh
RISCV_HEX="$PWD/src/riscv/rv32ui-p-add.hex" \
  sbt -batch -Dsbt.color=false 'testOnly riscvtests.RiscTest'
```

`RiscvTests.scala` 读取 `RISCV_HEX` 并传给 `Top(initFile=...)`。环境变量可以继承到 fork 出来的测试 JVM。无需修改 Memory 的默认路径，也无需复制到一个固定文件名。

判定规则：

1. 在周期上限内等到 `io.exit=true`，否则超时失败。
2. 到达退出位置后检查 `io.gp==1`，其他结果码失败。
3. 脚本同时检查 sbt 返回码和实际测试通过数量，避免 `No tests to run` 被误报为 PASS。

单项失败后继续运行其他项。所有项通过时脚本返回 0，存在失败或启动错误时返回非零状态，可供后续自动化调用。每次运行产生独立日志目录：

```text
target/riscv-tests/run-XXXXXX/
├── rv32ui-p-add.log
├── ...
└── summary.tsv          # 每项的 PASS/FAIL
```

## 4. 超时与调试

```sh
# 增加每个程序的最大仿真周期数（默认 10000）
RISCV_MAX_CYCLES=20000 ./scripts/riscv-tests.sh lw sw

# 开启 Core 的逐周期 printf，日志写入对应 .log 文件
RISCV_DEBUG=true ./scripts/riscv-tests.sh add

# 直接运行 ScalaTest：未设置 RISCV_HEX 时默认加载 ADD 的镜像
sbt 'testOnly riscvtests.RiscTest'
```

`RISCV_MAX_CYCLES` 必须是正的 Int，`RISCV_DEBUG` 只接受 `true` 或 `false`，默认 `false`。周期上限限制的是 CPU 仿真周期数，不是 sbt 编译或仿真器构建的实际耗时。

如果程序失败，先查看对应日志中的超时信息或 gp 断言，再针对该程序开启调试。不要为了得到 PASS 而取消退出检查或结果码检查。这些测试也不代表 CPU 已通过完整 RISC-V 特权规范验证。
