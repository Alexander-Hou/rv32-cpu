package rv32cpu

import chisel3._
import chisel3.util._
import common.Instructions._
import common.Consts._

class Core(debug: Boolean = false) extends Module {
  val io = IO(
    new Bundle {
      // Flipped 将存储器侧定义的方向反转。
      val imem = Flipped(new ImemPortIO())
      val dmem = Flipped(new DmemPortIO())

      val exit = Output(Bool())
      val gp = Output(UInt(WORD_LEN.W))
    }
  )

  val regfile = Mem(32, UInt(WORD_LEN.W))  // 通用寄存器组
  val csr_regfile = Mem(4096, UInt(WORD_LEN.W))  // CSR 存储

  // **********************************************************
  // IF阶段

  // 最基本取指路径
  val pc_reg = RegInit(START_ADDR)  // PC
  io.imem.addr := pc_reg  // 将当前 PC 送到指令存储器
  val inst = io.imem.inst  // 指令存储器返回指令
  val pc_plus4 = pc_reg + 4.U(WORD_LEN.W)  // PC + 4

  val br_target = Wire(UInt(WORD_LEN.W))  // 条件分支成立时要跳转的地址
  val br_flg = Wire(Bool())  // 分支是否成立
  val jmp_flg = (inst === JAL || inst === JALR)  // 判断是否为无条件跳转指令
  val alu_out = Wire(UInt(WORD_LEN.W))  // ALU 的计算结果

  // 下一 PC 的选择
  val pc_next = MuxCase(
    pc_plus4,
    Seq(
      br_flg -> br_target,
      jmp_flg -> alu_out,
      (inst === ECALL) -> csr_regfile(0x305) // 前往陷阱向量
    )
  )
  pc_reg := pc_next

  // **********************************************************
  // ID阶段

  // 提取寄存器编号
  val rs1_addr = inst(19, 15)  // 提取 r1 编号
  val rs2_addr = inst(24, 20)  // 提取 r2 编号
  val wb_addr = inst(11, 7)  // 提取 rd 编号，这里强调写回，命名为 wb_addr
  
  // 读取源寄存器同时处理 x0
  val rs1_data =
    Mux((rs1_addr =/= 0.U(WORD_LEN.W)), regfile(rs1_addr), 0.U(WORD_LEN.W))
  val rs2_data =
    Mux((rs2_addr =/= 0.U(WORD_LEN.W)), regfile(rs2_addr), 0.U(WORD_LEN.W))

  // 生成不同的格式的立即数
  val imm_i = inst(31, 20)
  val imm_i_sext = Cat(Fill(20, imm_i(11)), imm_i)
  val imm_s = Cat(inst(31, 25), inst(11, 7))
  val imm_s_sext = Cat(Fill(20, imm_s(11)), imm_s)
  val imm_b = Cat(inst(31), inst(7), inst(30, 25), inst(11, 8))
  val imm_b_sext = Cat(Fill(19, imm_b(11)), imm_b, 0.U(1.W))
  val imm_j = Cat(inst(31), inst(19, 12), inst(20), inst(30, 21))
  val imm_j_sext = Cat(Fill(11, imm_j(19)), imm_j, 0.U(1.W))
  val imm_u = inst(31, 12)
  val imm_u_shifted = Cat(imm_u, Fill(12, 0.U))
  val imm_z = inst(19, 15)
  val imm_z_uext = Cat(Fill(27, 0.U), imm_z)


  // ListLookup 根据指令匹配结果，输出控制字段
  val csignals = ListLookup(
    inst,
    List(ALU_X, OP1_RS1, OP2_RS2, MEN_X, REN_X, WB_X, CSR_X),
    Array(
      LW -> List(ALU_ADD, OP1_RS1, OP2_IMI, MEN_X, REN_S, WB_MEM, CSR_X),
      SW -> List(ALU_ADD, OP1_RS1, OP2_IMS, MEN_S, REN_X, WB_X, CSR_X),
      ADD -> List(ALU_ADD, OP1_RS1, OP2_RS2, MEN_X, REN_S, WB_ALU, CSR_X),
      ADDI -> List(ALU_ADD, OP1_RS1, OP2_IMI, MEN_X, REN_S, WB_ALU, CSR_X),
      SUB -> List(ALU_SUB, OP1_RS1, OP2_RS2, MEN_X, REN_S, WB_ALU, CSR_X),
      AND -> List(ALU_AND, OP1_RS1, OP2_RS2, MEN_X, REN_S, WB_ALU, CSR_X),
      OR -> List(ALU_OR, OP1_RS1, OP2_RS2, MEN_X, REN_S, WB_ALU, CSR_X),
      XOR -> List(ALU_XOR, OP1_RS1, OP2_RS2, MEN_X, REN_S, WB_ALU, CSR_X),
      ANDI -> List(ALU_AND, OP1_RS1, OP2_IMI, MEN_X, REN_S, WB_ALU, CSR_X),
      ORI -> List(ALU_OR, OP1_RS1, OP2_IMI, MEN_X, REN_S, WB_ALU, CSR_X),
      XORI -> List(ALU_XOR, OP1_RS1, OP2_IMI, MEN_X, REN_S, WB_ALU, CSR_X),
      SLL -> List(ALU_SLL, OP1_RS1, OP2_RS2, MEN_X, REN_S, WB_ALU, CSR_X),
      SRL -> List(ALU_SRL, OP1_RS1, OP2_RS2, MEN_X, REN_S, WB_ALU, CSR_X),
      SRA -> List(ALU_SRA, OP1_RS1, OP2_RS2, MEN_X, REN_S, WB_ALU, CSR_X),
      SLLI -> List(ALU_SLL, OP1_RS1, OP2_IMI, MEN_X, REN_S, WB_ALU, CSR_X),
      SRLI -> List(ALU_SRL, OP1_RS1, OP2_IMI, MEN_X, REN_S, WB_ALU, CSR_X),
      SRAI -> List(ALU_SRA, OP1_RS1, OP2_IMI, MEN_X, REN_S, WB_ALU, CSR_X),
      SLT -> List(ALU_SLT, OP1_RS1, OP2_RS2, MEN_X, REN_S, WB_ALU, CSR_X),
      SLTU -> List(ALU_SLTU, OP1_RS1, OP2_RS2, MEN_X, REN_S, WB_ALU, CSR_X),
      SLTI -> List(ALU_SLT, OP1_RS1, OP2_IMI, MEN_X, REN_S, WB_ALU, CSR_X),
      SLTIU -> List(ALU_SLTU, OP1_RS1, OP2_IMI, MEN_X, REN_S, WB_ALU, CSR_X),
      BEQ -> List(BR_BEQ, OP1_RS1, OP2_RS2, MEN_X, REN_X, WB_X, CSR_X),
      BNE -> List(BR_BNE, OP1_RS1, OP2_RS2, MEN_X, REN_X, WB_X, CSR_X),
      BGE -> List(BR_BGE, OP1_RS1, OP2_RS2, MEN_X, REN_X, WB_X, CSR_X),
      BGEU -> List(BR_BGEU, OP1_RS1, OP2_RS2, MEN_X, REN_X, WB_X, CSR_X),
      BLT -> List(BR_BLT, OP1_RS1, OP2_RS2, MEN_X, REN_X, WB_X, CSR_X),
      BLTU -> List(BR_BLTU, OP1_RS1, OP2_RS2, MEN_X, REN_X, WB_X, CSR_X),
      JAL -> List(ALU_ADD, OP1_PC, OP2_IMJ, MEN_X, REN_S, WB_PC, CSR_X),
      JALR -> List(ALU_JALR, OP1_RS1, OP2_IMI, MEN_X, REN_S, WB_PC, CSR_X),
      LUI -> List(ALU_ADD, OP1_X, OP2_IMU, MEN_X, REN_S, WB_ALU, CSR_X),
      AUIPC -> List(ALU_ADD, OP1_PC, OP2_IMU, MEN_X, REN_S, WB_ALU, CSR_X),
      CSRRW -> List(ALU_COPY1, OP1_RS1, OP2_X, MEN_X, REN_S, WB_CSR, CSR_W),
      CSRRWI -> List(ALU_COPY1, OP1_IMZ, OP2_X, MEN_X, REN_S, WB_CSR, CSR_W),
      CSRRS -> List(ALU_COPY1, OP1_RS1, OP2_X, MEN_X, REN_S, WB_CSR, CSR_S),
      CSRRSI -> List(ALU_COPY1, OP1_IMZ, OP2_X, MEN_X, REN_S, WB_CSR, CSR_S),
      CSRRC -> List(ALU_COPY1, OP1_RS1, OP2_X, MEN_X, REN_S, WB_CSR, CSR_C),
      CSRRCI -> List(ALU_COPY1, OP1_IMZ, OP2_X, MEN_X, REN_S, WB_CSR, CSR_C),
      ECALL -> List(ALU_X, OP1_X, OP2_X, MEN_X, REN_X, WB_X, CSR_E)
    )
  )

  // 产生控制信号
  val exe_fun :: op1_sel :: op2_sel :: mem_wen :: rf_wen :: wb_sel :: csr_cmd :: Nil =
    csignals

  // 选择数据
  val op1_data = MuxCase(
    0.U(WORD_LEN.W),
    Seq(
      (op1_sel === OP1_RS1) -> rs1_data,
      (op1_sel === OP1_PC) -> pc_reg,
      (op1_sel === OP1_IMZ) -> imm_z_uext
    )
  )

  // 选择数据
  val op2_data = MuxCase(
    0.U(WORD_LEN.W),
    Seq(
      (op2_sel === OP2_RS2) -> rs2_data,
      (op2_sel === OP2_IMI) -> imm_i_sext,
      (op2_sel === OP2_IMS) -> imm_s_sext,
      (op2_sel === OP2_IMJ) -> imm_j_sext,
      (op2_sel === OP2_IMU) -> imm_u_shifted
    )
  )

  // ******************************************
  // EX 阶段

  // 控制 ALU 结果选择
  alu_out := MuxCase(
    0.U(WORD_LEN.W),
    Seq(
      (exe_fun === ALU_ADD) -> (op1_data + op2_data),
      (exe_fun === ALU_SUB) -> (op1_data - op2_data),
      (exe_fun === ALU_AND) -> (op1_data & op2_data),
      (exe_fun === ALU_OR) -> (op1_data | op2_data),
      (exe_fun === ALU_XOR) -> (op1_data ^ op2_data),
      (exe_fun === ALU_SLL) -> (op1_data << op2_data(4, 0))(31, 0),
      (exe_fun === ALU_SRL) -> (op1_data >> op2_data(4, 0)).asUInt,
      (exe_fun === ALU_SRA) -> (op1_data.asSInt >> op2_data(4, 0)).asUInt,
      (exe_fun === ALU_SLT) -> (op1_data.asSInt < op2_data.asSInt).asUInt,
      (exe_fun === ALU_SLTU) -> (op1_data < op2_data).asUInt,
      (exe_fun === ALU_JALR) -> ((op1_data + op2_data) & ~1.U(WORD_LEN.W)),
      (exe_fun === ALU_COPY1) -> op1_data
    )
  )

  // 分支
  br_target := pc_reg + imm_b_sext
  br_flg := MuxCase(
    false.B,
    Seq(
      (exe_fun === BR_BEQ) -> (op1_data === op2_data),
      (exe_fun === BR_BNE) -> !(op1_data === op2_data),
      (exe_fun === BR_BLT) -> (op1_data.asSInt < op2_data.asSInt),
      (exe_fun === BR_BGE) -> !(op1_data.asSInt < op2_data.asSInt),
      (exe_fun === BR_BLTU) -> (op1_data < op2_data),
      (exe_fun === BR_BGEU) -> !(op1_data < op2_data)
    )
  )

  // ******************************************
  // MEM 阶段

  // 将地址送入内存，mem_wen 决定是否写回
  io.dmem.addr := alu_out
  io.dmem.wen := mem_wen
  io.dmem.wdata := rs2_data

  // CSR
  val csr_addr = Mux(csr_cmd === CSR_E, 0x342.U(CSR_ADDR_LEN.W), inst(31, 20))
  val is_mhartid = csr_addr === 0xf14.U(CSR_ADDR_LEN.W)
  val csr_rdata = Mux(
  is_mhartid,
  0.U(WORD_LEN.W),
  csr_regfile(csr_addr)
)
  val csr_wdata = MuxCase(
    0.U(WORD_LEN.W),
    Seq(
      (csr_cmd === CSR_W) -> op1_data,
      (csr_cmd === CSR_S) -> (csr_rdata | op1_data),
      (csr_cmd === CSR_C) -> (csr_rdata & ~op1_data),
      (csr_cmd === CSR_E) -> 11.U(WORD_LEN.W)
    )
  )

  when(csr_cmd > 0.U) {
    csr_regfile(csr_addr) := csr_wdata
  }

  // ******************************************
  // WB 阶段

  // 控制写回选择器
  val wb_data = MuxCase(
    alu_out,
    Seq(
      (wb_sel === WB_MEM) -> io.dmem.rdata,
      (wb_sel === WB_PC) -> pc_plus4,
      (wb_sel === WB_CSR) -> csr_rdata
    )
  )

  when(rf_wen === REN_S) {
    regfile(wb_addr) := wb_data
  }

  // ******************************************
  // 测试观察接口始终保留，不受日志开关影响。
  io.gp := regfile(3)
  io.exit := (pc_reg === 0x44.U(WORD_LEN.W))

  // Scala 在生成电路时决定是否包含日志；这不是运行时的硬件开关。
  // 排查问题时使用 new Core(debug = true) 开启逐周期详细输出。
  if (debug) {
    printf(p"io.pc : 0x${Hexadecimal(pc_reg)}\n")
    printf(p"inst : 0x${Hexadecimal(inst)}\n")
    printf(p"gp : ${regfile(3)}\n")
    printf(p"rs1_addr : $rs1_addr\n")
    printf(p"rs2_addr : $rs2_addr\n")
    printf(p"wb_addr : $wb_addr\n")
    printf(p"rs1_data : 0x${Hexadecimal(rs1_data)}\n")
    printf(p"rs2_data : 0x${Hexadecimal(rs2_data)}\n")
    printf(p"wb_data : 0x${Hexadecimal(wb_data)}\n")
    printf(p"dmem.addr : ${io.dmem.addr}\n")
    printf(p"dmem.rdata : ${io.dmem.rdata}\n")
    printf("----------\n")
  }
}
