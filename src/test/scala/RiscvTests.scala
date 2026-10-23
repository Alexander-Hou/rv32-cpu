package riscvtests

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import rv32cpu.Top

class RiscTest extends AnyFlatSpec with ChiselSim {
  "rv32-cpu" should "work through hex" in {
    // 批量脚本逐个设置镜像路径；直接运行 sbt 时默认检查 ADD。
    // 环境变量可传给 fork 出来的测试 JVM，而 sbt 自身的 -D 参数不一定会转发。
    val hexFile = new java.io.File(
      sys.env.getOrElse("RISCV_HEX", "src/riscv/rv32ui-p-add.hex")
    ).getAbsoluteFile
    require(hexFile.isFile && hexFile.length() > 0, s"Missing HEX file: $hexFile; run scripts/tohex.sh first")
    val maxCycles = sys.env.getOrElse("RISCV_MAX_CYCLES", "10000").toInt
    require(maxCycles > 0, "RISCV_MAX_CYCLES must be positive")
    val debugSetting = sys.env.getOrElse("RISCV_DEBUG", "false")
    require(Set("true", "false").contains(debugSetting), "RISCV_DEBUG must be true or false")
    info(s"Program: ${hexFile.getName}; maxCycles=$maxCycles; debug=$debugSetting")

    simulate(new Top(hexFile.getPath, debug = debugSetting.toBoolean)) { c =>
      var cycles = 0

      while (!c.io.exit.peek().litToBoolean && cycles < maxCycles) {
        c.clock.step(1)
        cycles += 1
      }

      assert(
        c.io.exit.peek().litToBoolean,
        s"${hexFile.getName}: CPU did not reach the exit address within $maxCycles cycles (executed $cycles cycles)"
      )
      c.io.gp.expect(1.U)
    }
  }
}
