package topspec

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.exceptions.TestFailedException
import org.scalatest.flatspec.AnyFlatSpec
import rv32cpu.Top

class TopSpec extends AnyFlatSpec with ChiselSim {
  private def program(name: String): String =
    java.nio.file.Paths.get(getClass.getResource(s"/top/$name.hex").toURI).toString

  // exit 表示到达 Core 约定的地址 0x44；gp=1 才代表成功。
  // 先检查是否退出，避免把超时后碰巧为 1 的 gp 当成成功。
  private def runUntilSuccess(c: Top, maxCycles: Int): Unit = {
    var cycles = 0
    while (!c.io.exit.peek().litToBoolean && cycles < maxCycles) {
      c.clock.step(1)
      cycles += 1
    }
    assert(c.io.exit.peek().litToBoolean, s"CPU timeout after $maxCycles cycles")
    val result = c.io.gp.peek().litValue
    assert(result == 1, s"CPU reported failure: gp=$result")
  }

  "Top" should "T1 execute a three instruction smoke program from Memory" in {
    simulate(new Top(program("smoke"))) { c =>
      // addi x1,x0,5；addi x3,x1,7；add x3,x3,x1。
      // 让真实的取指连线和 Memory 提供指令。
      c.io.exit.expect(false.B)
      c.clock.step(2)
      c.io.gp.expect(12.U)
      c.clock.step(1)
      c.io.gp.expect(17.U)
      c.io.exit.expect(false.B)
    }
  }

  it should "T2 accept completion and reject failure or timeout" in {
    simulate(new Top(program("pass"))) { c =>
      // 第三条指令跳至 0x44，恰好在周期上限退出也应通过。
      runUntilSuccess(c, maxCycles = 3)
    }
    simulate(new Top(program("fail"))) { c =>
      // 负例设置 gp=2 后退出，公共判定函数必须抛出失败异常。
      // intercept 只验证判定机制；正常运行程序时不捕获此异常。
      val failure = intercept[TestFailedException] {
        runUntilSuccess(c, maxCycles = 3)
      }
      assert(failure.message.exists(_.contains("CPU reported failure: gp=2")))
    }
    simulate(new Top(program("timeout"))) { c =>
      // gp=1 后死循环，证明仅有 gp=1 不能判定 PASS。
      val failure = intercept[TestFailedException] {
        runUntilSuccess(c, maxCycles = 5)
      }
      assert(failure.message.exists(_.contains("CPU timeout after 5 cycles")))
      c.io.exit.expect(false.B)
      c.io.gp.expect(1.U)
    }
  }
}
