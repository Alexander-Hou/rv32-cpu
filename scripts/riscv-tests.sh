#!/usr/bin/env bash
# 每个 HEX 启动一次 sbt 测试；单项失败后继续，最后汇总并返回非零状态。
set -euo pipefail
export LC_ALL=C

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
project_dir=$(cd -- "$script_dir/.." && pwd)
if [[ ${1:-} == --help || ${1:-} == -h ]]; then
  echo "用法：$0 [指令名 ...]"
  echo "无参数运行全部支持的测试；例如：$0 add beq lw"
  echo "先运行 scripts/tohex.sh 生成 HEX。--list 列出支持的测试。"
  echo "可选环境变量：RISCV_MAX_CYCLES（默认 10000）、RISCV_DEBUG（默认 false）。"
  exit 0
fi
if [[ ${1:-} == --list ]]; then
  exec bash "$script_dir/tohex.sh" --list
fi
# 从转换脚本读取同一份白名单，避免两份清单逐渐不一致。
list=$(bash "$script_dir/tohex.sh" --list)
mapfile -t tests <<< "$list"
selected=("${tests[@]}")
if (( $# > 0 )); then selected=("$@"); fi
for name in "${selected[@]}"; do
  if [[ ! $name =~ ^[a-z0-9_]+$ || " ${tests[*]} " != *" $name "* ]]; then
    echo "不支持的测试：$name（使用 --list 查看）" >&2
    exit 1
  fi
  if [[ ! -s "$project_dir/src/riscv/rv32ui-p-$name.hex" ]]; then
    echo "缺少 HEX：rv32ui-p-$name.hex，请先运行 scripts/tohex.sh。" >&2
    exit 1
  fi
done
command -v sbt >/dev/null 2>&1 || { echo "找不到 sbt。" >&2; exit 1; }

cd -- "$project_dir"
mkdir -p target/riscv-tests
log_dir=$(mktemp -d "$project_dir/target/riscv-tests/run-XXXXXX")
printf '测试日志：%s\n' "$log_dir"
printf 'test\tresult\n' > "$log_dir/summary.tsv"
passed=0
failed=0
for name in "${selected[@]}"; do
  base="rv32ui-p-$name"
  log="$log_dir/$base.log"
  printf '运行 %s ...\n' "$base"
  # 环境变量会继承到 Test / fork 启动的 JVM，不依赖 sbt 的 -D 转发。
  # 显式覆盖 RISCV_HEX，避免外部设置导致所有用例都运行同一个镜像。
  if RISCV_HEX="$project_dir/src/riscv/$base.hex" \
      sbt -batch -Dsbt.color=false 'testOnly riscvtests.RiscTest' > "$log" 2>&1; then
    # sbt 在没有找到测试时也可能返回 0，因此还要确认实际通过了一个测试。
    if grep -Eq 'Tests: succeeded 1, failed 0, canceled 0, ignored 0, pending 0' "$log"; then
      printf 'PASS %s\n' "$base"
      printf '%s\tPASS\n' "$base" >> "$log_dir/summary.tsv"
      passed=$((passed + 1))
      continue
    fi
  fi
  printf 'FAIL %s（日志：%s）\n' "$base" "$log"
  tail -n 15 "$log"
  printf '%s\tFAIL\n' "$base" >> "$log_dir/summary.tsv"
  failed=$((failed + 1))
done
printf '\n总计 %s：PASS=%s FAIL=%s\n汇总：%s/summary.tsv\n' "${#selected[@]}" "$passed" "$failed" "$log_dir"
(( failed == 0 ))
