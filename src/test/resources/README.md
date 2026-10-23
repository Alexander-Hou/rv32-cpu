# 模块测试用的 HEX 文件

Memory 的每个元素是 8 位，因此文件每行表示一个字节，32 位指令按小端顺序保存。例如 `0x00500193` 保存为 `93 01 50 00`（每个字节单独一行）。

| 文件 | 指令与用途 |
| --- | --- |
| `memory/init.hex` | 地址 0：`addi x3,x0,5`；地址 4：`addi x3,x3,7`。用于初始化、字节顺序、组合读和写入测试。 |
| `top/smoke.hex` | `addi x1,x0,5` → `addi x3,x1,7` → `add x3,x3,x1`，x3 依次为 12、17；随后自循环。 |
| `top/pass.hex` | `addi x3,x0,0` → `addi x3,x3,1` → `jal x0,+60`。第三个周期跳到 0x44，结果码为 1。 |
| `top/fail.hex` | 与 pass 相同，但第二条指令加 2，到达 0x44 时结果码为 2。 |
| `top/timeout.hex` | `addi x3,x0,1` 后在地址 4 自循环。结果码虽然为 1，但未到 0x44，必须超时失败。 |

pass/fail 中未执行的位置填 NOP，0x44 放置自循环指令。Top 当前以 PC=0x44 作为退出标志，测试判定函数同时要求 x3=1。

运行这次定义的 10 项测试：

```sh
sbt "testOnly memoryspec.MemorySpec corespec.CoreSpec topspec.TopSpec"
```

T2 中使用 `intercept` 确认失败程序和死循环程序确实触发测试失败异常。因此 T2 通过意味着成功、失败、超时三种判定均符合预期。
