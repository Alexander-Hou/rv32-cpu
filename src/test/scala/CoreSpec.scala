package corespec

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import common.Consts._
import rv32cpu.Core

class CoreSpec extends AnyFlatSpec with ChiselSim {
  // 直接注入指令，不实例化 Memory；这些指令均不读取数据内存。
  private def execute(c: Core, instruction: String): Unit = {
    c.io.imem.inst.poke(instruction.U)
    c.io.dmem.wen.expect(MEN_X)
    c.clock.step(1)
  }

  "Core" should "C1 reset the PC and advance it by four for each NOP" in {
    simulate(new Core) { c =>
      c.io.dmem.rdata.poke(0.U)
      c.io.imem.inst.poke(BUBBLE) // NOP = addi x0,x0,0
      // 显式施加复位，使检查的起点清晰可见。
      c.reset.poke(true.B)
      c.clock.step(1)
      c.reset.poke(false.B)
      c.io.imem.addr.expect(START_ADDR)
      for (n <- 1 to 3) {
        c.io.dmem.wen.expect(MEN_X)
        c.clock.step(1)
        c.io.imem.addr.expect((START_ADDR.litValue + 4 * n).U)
      }
    }
  }

  it should "C2 keep x0 observably zero and reuse register writeback" in {
    simulate(new Core) { c =>
      c.io.dmem.rdata.poke(0.U)
      execute(c, "h00900013") // addi x0,x0,9：尝试写入 x0
      execute(c, "h00500193") // addi x3,x0,5：通过 rs1 读取 x0
      c.io.gp.expect(5.U)
      execute(c, "h00718193") // addi x3,x3,7：使用上条指令的写回值
      c.io.gp.expect(12.U)
      execute(c, "h000181B3") // add x3,x3,x0：通过 rs2 读取 x0
      c.io.gp.expect(12.U)
      // 检查软件可见的恒零行为，不检查内部 Mem 的第 0 项。
    }
  }

  it should "C3 select sequential or branch PC according to BEQ" in {
    simulate(new Core) { c =>
      c.io.dmem.rdata.poke(0.U)
      execute(c, "h00500093") // PC=0：addi x1,x0,5
      execute(c, "h00700113") // PC=4：addi x2,x0,7
      execute(c, "h00208863") // PC=8：beq x1,x2,+16，不成立
      c.io.imem.addr.expect(12.U)
      execute(c, "h00500113") // PC=12：addi x2,x0,5
      execute(c, "h00208863") // PC=16：beq x1,x2,+16，成立
      c.io.imem.addr.expect(32.U)
      execute(c, "hFE208CE3") // PC=32：beq x1,x2,-8，检查负偏移符号扩展
      c.io.imem.addr.expect(24.U)
    }
  }

  it should "C4 clear the JALR target low bit and write PC plus four" in {
    simulate(new Core) { c =>
      c.io.dmem.rdata.poke(0.U)
      execute(c, "h02000093") // PC=0：addi x1,x0,32
      // PC=4：jalr x3,5(x1)，32+5=37，清除最低位后跳至 36。
      execute(c, "h005081E7")
      c.io.imem.addr.expect(36.U)
      c.io.gp.expect(8.U) // 链接地址来自旧 PC+4，而不是目标+4
    }
  }
}
