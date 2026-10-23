#!/usr/bin/env bash
# 将当前 Core 支持的官方测试 ELF 转为 BIN，再转为逐字节 HEX。
set -euo pipefail
export LC_ALL=C

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
project_dir=$(cd -- "$script_dir/.." && pwd)
isa_dir="$project_dir/src/prebuilt/share/riscv-tests/isa"
out_dir="$project_dir/src/riscv"

# 这是两个脚本共用的测试白名单；simple 用来检查官方测试启动流程。
tests=(simple add addi sub and andi or ori xor xori
       sll slli srl srli sra srai slt slti sltu sltiu
       lui auipc beq bne blt bge bltu bgeu jal jalr lw sw)

if [[ ${1:-} == --help || ${1:-} == -h ]]; then
  echo "用法：$0 [指令名 ...]"
  echo "无参数转换全部支持的测试；例如：$0 add beq lw"
  echo "--list 列出支持的指令名。OBJCOPY 可指定 objcopy 可执行文件。"
  exit 0
fi
if [[ ${1:-} == --list ]]; then
  printf '%s\n' "${tests[@]}"
  exit 0
fi

selected=("${tests[@]}")
if (( $# > 0 )); then selected=("$@"); fi
# 转换前检查所有参数和输入，避免拼错名字却仍显示成功。
for name in "${selected[@]}"; do
  if [[ ! $name =~ ^[a-z0-9_]+$ || " ${tests[*]} " != *" $name "* ]]; then
    echo "不支持的测试：$name（使用 --list 查看）" >&2
    exit 1
  fi
  if [[ ! -f "$isa_dir/rv32ui-p-$name" ]]; then
    echo "缺少官方 ELF：$isa_dir/rv32ui-p-$name" >&2
    exit 1
  fi
done

# 使用 RISC-V GCC 工具链配套的 objcopy，不自动回退到其他工具。
if [[ -z ${OBJCOPY:-} ]]; then
  for candidate in riscv32-unknown-elf-objcopy riscv64-unknown-elf-objcopy riscv64-linux-gnu-objcopy; do
    if command -v "$candidate" >/dev/null 2>&1; then
      OBJCOPY=$candidate
      break
    fi
  done
fi
if [[ -z ${OBJCOPY:-} ]]; then
  echo "未找到 RISC-V objcopy：请将 RISC-V GCC 工具链的 bin/ 加入 PATH，或设置 OBJCOPY 为工具的绝对路径。" >&2
  exit 1
fi
for tool in "$OBJCOPY" od readelf awk; do
  command -v "$tool" >/dev/null 2>&1 || { echo "缺少工具：$tool" >&2; exit 1; }
done

mkdir -p -- "$out_dir"
# 先写临时文件，转换成功后才替换输出，避免留下截断的 HEX。
tmp_dir=$(mktemp -d "$out_dir/.tohex.XXXXXX")
trap 'rm -rf -- "$tmp_dir"' EXIT
for name in "${selected[@]}"; do
  base="rv32ui-p-$name"
  elf="$isa_dir/$base"
  header=$(readelf -h "$elf")
  if ! grep -Eq 'Class: +ELF32' <<< "$header" ||
     ! grep -Eq 'Machine: +RISC-V' <<< "$header" ||
     ! grep -q 'little endian' <<< "$header" ||
     ! grep -Eq 'Entry point address: +0x0$' <<< "$header"; then
    echo "$base 必须是入口地址为 0 的 RV32 小端 ELF；请重新链接，不能只修改文件偏移。" >&2
    exit 1
  fi

  # 保留所有可加载内容和段间间隙，不能仅提取 .text，否则 LW/SW 缺少数据。
  "$OBJCOPY" -O binary "$elf" "$tmp_dir/$base.bin"
  size=$(wc -c < "$tmp_dir/$base.bin")
  if (( size == 0 || size > 16384 )); then
    echo "$base 镜像大小 $size 字节，不符合当前 16 KiB Memory 的容量。" >&2
    exit 1
  fi
  # -v 禁止将重复行缩成 *；-An 去掉地址；-tx1 -w1 每行输出一个十六进制字节。
  od -An -v -tx1 -w1 "$tmp_dir/$base.bin" | awk '{print $1}' > "$tmp_dir/$base.hex"
  mv -- "$tmp_dir/$base.bin" "$out_dir/$base.bin"
  mv -- "$tmp_dir/$base.hex" "$out_dir/$base.hex"
  printf '转换完成：%s (%s bytes)\n' "$base" "$size"
done
printf '共转换 %s 个测试，输出目录：%s\n' "${#selected[@]}" "$out_dir"
