from pathlib import Path
from datetime import date
from PIL import Image, ImageDraw, ImageFont
from docx import Document
from docx.shared import Inches, Pt, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.section import WD_SECTION
from docx.enum.table import WD_TABLE_ALIGNMENT, WD_CELL_VERTICAL_ALIGNMENT
from docx.oxml import OxmlElement
from docx.oxml.ns import qn

ROOT = Path(__file__).resolve().parent
OUT = ROOT / '小型医院住院信息管理与费用结算系统—需求分析与设计.docx'
ASSET = ROOT / '_requirements_assets'
ASSET.mkdir(exist_ok=True)

NAVY='17365D'; BLUE='2F75B5'; TEAL='0F766E'; LIGHT='EAF2F8'; PALE='F6F9FC'; GOLD='C98D20'; GRAY='5B6573'; LINE='B8C7D9'; WHITE='FFFFFF'; RED='A63D40'
FONT = r'C:\Windows\Fonts\msyh.ttc'
FONT_B = r'C:\Windows\Fonts\msyhbd.ttc'
def pc(x): return x if str(x).startswith('#') else '#'+str(x)

def f(size, bold=False):
    try: return ImageFont.truetype(FONT_B if bold else FONT, size)
    except: return ImageFont.load_default()

def center(draw, box, text, font, fill, outline=LINE, text_fill=NAVY, radius=18):
    draw.rounded_rectangle(box, radius=radius, fill=pc(fill), outline=pc(outline), width=2)
    bb=draw.multiline_textbbox((0,0), text, font=font, spacing=5, align='center')
    x=(box[0]+box[2]-(bb[2]-bb[0]))/2; y=(box[1]+box[3]-(bb[3]-bb[1]))/2
    draw.multiline_text((x,y), text, font=font, fill=pc(text_fill), spacing=5, align='center')

def arrow(draw, a, b, label='', color=GRAY):
    draw.line([a,b], fill=pc(color), width=3)
    import math
    ang=math.atan2(b[1]-a[1],b[0]-a[0]); L=12
    p1=(b[0]-L*math.cos(ang-.5), b[1]-L*math.sin(ang-.5)); p2=(b[0]-L*math.cos(ang+.5), b[1]-L*math.sin(ang+.5))
    draw.polygon([b,p1,p2],fill=pc(color))
    if label:
        draw.text(((a[0]+b[0])/2+5,(a[1]+b[1])/2-22),label,font=f(20,True),fill=pc(color))

def usecase_png(path):
    im=Image.new('RGB',(1800,1420),'white'); d=ImageDraw.Draw(im)
    d.text((70,38),'系统用例图：角色与核心业务功能',font=f(42,True),fill=pc(NAVY))
    d.rounded_rectangle((420,115,1715,1340),20,outline=pc(BLUE),width=3,fill='#F9FCFF')
    d.text((465,138),'小型医院住院信息管理与费用结算系统',font=f(28,True),fill=pc(BLUE))
    actors=[('系统管理员',(85,180)),('住院处人员',(85,405)),('医生',(85,650)),('护士 / 药房',(85,895)),('收费人员',(85,1140))]
    for name,(x,y) in actors:
        d.ellipse((x+70,y,x+120,y+50),outline=pc(NAVY),width=4)
        d.line((x+95,y+50,x+95,y+120),fill=pc(NAVY),width=4); d.line((x+55,y+82,x+135,y+82),fill=pc(NAVY),width=4); d.line((x+95,y+120,x+55,y+170),fill=pc(NAVY),width=4); d.line((x+95,y+120,x+135,y+170),fill=pc(NAVY),width=4)
        d.text((x+15,y+180),name,font=f(25,True),fill=pc(NAVY))
    boxes=[
      ('基础数据、账号\n与审计管理',(560,205,940,320),0),
      ('患者建档 / 入院\n分床、转床、转科',(560,430,940,545),1), ('出院申请\n床位状态维护',(1110,430,1490,545),1),
      ('开立、提交、停止\n撤销医嘱',(560,675,940,790),2),
      ('待执行任务\n给药 / 发药 / 执行',(560,920,940,1035),3), ('库存扣减、退药\n费用冲正',(1110,920,1490,1035),3),
      ('押金登记、补交\n欠费提醒',(560,1165,940,1280),4), ('结算预检、出院结算\n退款与结果查询',(1110,1165,1490,1280),4),
    ]
    amap={0:(205,270),1:(205,495),2:(205,740),3:(205,985),4:(205,1230)}
    for txt,box,a in boxes:
        center(d,box,txt,f(25,True),'EAF2F8',text_fill=NAVY)
        arrow(d,amap[a],(box[0],(box[1]+box[3])//2))
    d.text((500,1300),'说明：系统管理员可管理基础数据、用户权限与审计；其余角色按职责访问相应业务。后端权限校验是最终边界。',font=f(21),fill=pc(GRAY))
    im.save(path)

def er_png(path):
    im=Image.new('RGB',(1900,1380),'white'); d=ImageDraw.Draw(im)
    d.text((55,32),'核心 ER 图（概念层）',font=f(42,True),fill=pc(NAVY))
    ent={
      '患者\nPATIENT':(70,250,285,355),'住院记录\nADMISSION':(380,250,630,355),'床位使用\nBED_USAGE':(755,120,985,225),'床位\nBED':(1110,120,1315,225),
      '医嘱\nMEDICAL_ORDER':(755,390,985,495),'医嘱项目\nORDER_ITEM':(1105,390,1335,495),'执行记录\nEXECUTION_RECORD':(1455,390,1770,495),
      '转床/转科记录\nTRANSFER_RECORD':(330,610,635,720),'押金流水\nDEPOSIT_TRANSACTION':(740,610,1010,720),'医疗项目\nMEDICAL_ITEM':(1095,610,1345,720),'费用明细\nCHARGE_DETAIL':(1455,610,1770,720),
      '结算单\nSETTLEMENT':(760,875,1010,985),'结算明细\nSETTLEMENT_DETAIL':(1140,875,1470,985),'库存流水\nDRUG_INVENTORY\nTRANSACTION':(1510,875,1810,1005)
    }
    for name,box in ent.items(): center(d,box,name,f(24,True),'F4F8FC',text_fill=NAVY)
    rel=[('1:N',(285,302),(380,302)),('1:N',(630,273),(755,190)),('N:1',(985,172),(1110,172)),
         ('',(630,325),(755,430)),('1:N',(985,442),(1105,442)),('1:N',(1335,442),(1455,442)),
         ('N:1',(1220,495),(1220,610)),('',(1612,495),(1612,610)),('',(1612,495),(1660,875)),
         ('',(505,355),(485,610)),('',(620,348),(820,610)),('',(620,352),(880,875)),('1:N',(885,985),(1140,930))]
    for lab,a,b in rel:
        d.line([a,b],fill=pc(GRAY),width=3)
        mx=(a[0]+b[0])//2; my=(a[1]+b[1])//2
        d.rounded_rectangle((mx-26,my-18,mx+26,my+18),8,fill='#FFFFFF')
        d.text((mx-18,my-12),lab,font=f(16,True),fill=pc(GRAY))
    d.rounded_rectangle((65,1140,1830,1320),16,fill='#F9FCFF',outline=pc(LINE),width=2)
    note='读图规则：实体框表示业务对象；连线旁标注关系基数。\n1:N 表示一个父实体可对应多条子记录；一次住院与结算单为 0..1:1；费用、押金、库存均以流水方式保留历史。'
    d.multiline_text((110,1180),note,font=f(25),fill=pc(NAVY),spacing=14)
    im.save(path)

def shade(cell, color):
    tcPr=cell._tc.get_or_add_tcPr(); shd=OxmlElement('w:shd'); shd.set(qn('w:fill'),color); tcPr.append(shd)
def border(cell, color='D6E0EA'):
    tcPr=cell._tc.get_or_add_tcPr(); borders=tcPr.first_child_found_in('w:tcBorders')
    if borders is None: borders=OxmlElement('w:tcBorders'); tcPr.append(borders)
    for e in ['top','left','bottom','right']:
        el=OxmlElement('w:'+e); el.set(qn('w:val'),'single'); el.set(qn('w:sz'),'6'); el.set(qn('w:color'),color); borders.append(el)
def set_cell(cell, text, bold=False, color=NAVY, size=9.3):
    cell.text=''; p=cell.paragraphs[0]; p.paragraph_format.space_after=Pt(2); p.paragraph_format.space_before=Pt(2)
    r=p.add_run(text); r.bold=bold; r.font.size=Pt(size); r.font.color.rgb=RGBColor.from_string(color); r.font.name='Microsoft YaHei'; r._element.rPr.rFonts.set(qn('w:eastAsia'),'Microsoft YaHei')
    cell.vertical_alignment=WD_CELL_VERTICAL_ALIGNMENT.CENTER; border(cell)
def table(doc, headers, rows, widths=None):
    t=doc.add_table(rows=1, cols=len(headers)); t.alignment=WD_TABLE_ALIGNMENT.CENTER; t.autofit=False
    for i,h in enumerate(headers):
        c=t.rows[0].cells[i]; set_cell(c,h,True,WHITE,9.5); shade(c,NAVY)
        if widths: c.width=Inches(widths[i])
    trPr = t.rows[0]._tr.get_or_add_trPr()
    tblHeader = OxmlElement('w:tblHeader'); tblHeader.set(qn('w:val'), 'true'); trPr.append(tblHeader)
    for row in rows:
        cells=t.add_row().cells
        for i,v in enumerate(row):
            set_cell(cells[i],str(v),False,NAVY,8.8)
            if widths: cells[i].width=Inches(widths[i])
    doc.add_paragraph().paragraph_format.space_after=Pt(2)
    return t
def add_image(path, width, alt):
    inline = doc.add_picture(str(path), width=width)
    inline._inline.docPr.set('descr', alt)
    inline._inline.docPr.set('title', alt)
    return inline

def set_run(run, size=10.5, bold=False, color='1F2937'):
    run.font.name='Microsoft YaHei'; run._element.rPr.rFonts.set(qn('w:eastAsia'),'Microsoft YaHei'); run.font.size=Pt(size); run.bold=bold; run.font.color.rgb=RGBColor.from_string(color)
def add_p(text='',style=None,before=0,after=6,align=None):
    p=doc.add_paragraph(style=style); p.paragraph_format.space_before=Pt(before); p.paragraph_format.space_after=Pt(after); p.paragraph_format.line_spacing=1.35
    if align: p.alignment=align
    r=p.add_run(text); set_run(r); return p
def bullet(text,level=0):
    p=doc.add_paragraph(style='List Bullet' if level==0 else 'List Bullet 2'); p.paragraph_format.space_after=Pt(3); p.paragraph_format.line_spacing=1.25; r=p.add_run(text); set_run(r,10.2); return p
def heading(text,level=1):
    p=doc.add_paragraph(style=f'Heading {level}'); p.paragraph_format.keep_with_next=True
    p.paragraph_format.space_before=Pt(15 if level==1 else 10); p.paragraph_format.space_after=Pt(6)
    r=p.add_run(text); set_run(r,16 if level==1 else 12,True,BLUE if level==1 else NAVY); return p
def note(text, label='课堂讲解提示'):
    t=doc.add_table(rows=1,cols=1); t.autofit=False; c=t.cell(0,0); shade(c,'FFF7E5'); border(c,'E5C57A'); c.text=''
    p=c.paragraphs[0]; p.paragraph_format.space_before=Pt(5); p.paragraph_format.space_after=Pt(5); r=p.add_run(label+'：'); set_run(r,10,True,GOLD); r=p.add_run(text); set_run(r,10,False,NAVY)
    doc.add_paragraph().paragraph_format.space_after=Pt(2)

usecase=ASSET/'usecase.png'; er=ASSET/'er.png'; usecase_png(usecase); er_png(er)
doc=Document()
sec=doc.sections[0]; sec.top_margin=Inches(.72); sec.bottom_margin=Inches(.7); sec.left_margin=Inches(.78); sec.right_margin=Inches(.78)
styles=doc.styles
styles['Normal'].font.name='Microsoft YaHei'; styles['Normal']._element.rPr.rFonts.set(qn('w:eastAsia'),'Microsoft YaHei'); styles['Normal'].font.size=Pt(10.5)
for n in ['Heading 1','Heading 2']:
    styles[n].font.name='Microsoft YaHei'; styles[n]._element.rPr.rFonts.set(qn('w:eastAsia'),'Microsoft YaHei')
# header/footer
header=sec.header.paragraphs[0]; header.alignment=WD_ALIGN_PARAGRAPH.RIGHT; r=header.add_run('小型医院住院信息管理与费用结算系统  |  需求分析与设计'); set_run(r,8.5,False,GRAY)
footer=sec.footer.paragraphs[0]; footer.alignment=WD_ALIGN_PARAGRAPH.CENTER; r=footer.add_run('数据库课程设计 · 第一次课堂演讲'); set_run(r,8.5,False,GRAY)

# Cover
for _ in range(5): add_p('',after=2)
p=add_p('数据库课程设计',before=0,after=14,align=WD_ALIGN_PARAGRAPH.CENTER); set_run(p.runs[0],14,True,GOLD)
p=add_p('小型医院住院信息管理与费用结算系统',after=10,align=WD_ALIGN_PARAGRAPH.CENTER); set_run(p.runs[0],27,True,NAVY)
p=add_p('系统需求分析与设计',after=28,align=WD_ALIGN_PARAGRAPH.CENTER); set_run(p.runs[0],20,False,BLUE)
table(doc,['课堂演讲主题','对应评分重点'],[['第一次课堂演讲','需求分析、用例图、ER 图、实体关系与数据库设计']], [2.1,4.4])
for _ in range(7): add_p('',after=2)
p=add_p('项目类型：数据库课程设计    技术实现：Spring Boot + MySQL + Vue',after=8,align=WD_ALIGN_PARAGRAPH.CENTER); set_run(p.runs[0],10.5,False,GRAY)
p=add_p('文档版本：V1.0    日期：2026 年 8 月',after=8,align=WD_ALIGN_PARAGRAPH.CENTER); set_run(p.runs[0],10.5,False,GRAY)
doc.add_page_break()

heading('目录')
toc=['1. 项目概述与系统边界','2. 需求分析（功能需求）','3. 用例模型与核心流程','4. 性能及其他非功能需求','5. 数据库设计目标与原则','6. 核心 ER 图与实体关系说明','7. 关键实体说明与关系映射','8. 演讲重点与总结']
for item in toc: bullet(item)
note('本报告以当前项目代码与数据库基线为依据。数据库已实现 30 张应用表、6 个视图、5 个存储过程和 20 个保护触发器；课堂讲解时以核心实体模型为主，完整物理表结构作为扩展说明。','使用范围')

heading('1. 项目概述与系统边界')
heading('1.1 建设背景与目标',2)
add_p('本系统面向小型医院住院业务，以“一次住院记录（Admission）”为主线，贯通患者入院、床位安排、临床医嘱、实际执行、费用记账、欠费预警和出院结算。设计目标不是覆盖所有医院业务，而是建立一个数据一致、过程可追溯、可演示的住院闭环。')
table(doc,['目标','对应设计'],[
 ['业务闭环','从入院分床到出院结算形成可验证流程'],['数据一致性','通过事务、外键、唯一约束、行锁和状态校验防止冲突'],['可追溯性','床位、执行、库存、费用、押金与结算保留历史流水'],['安全性','基于用户—角色—权限的访问控制，并记录审计日志'],['可分析性','支持床位使用率、收入构成、药品排行、住院天数等统计']], [1.45,5.05])
heading('1.2 系统范围',2)
table(doc,['纳入范围','暂不纳入范围'],[['患者建档、入院、分床、转床/转科','门诊、急诊、医保结算'],['药品/检查/治疗医嘱及执行','复杂电子病历、医学辅助诊断'],['押金、费用、欠费提醒、结算退款','线上支付、采购供应链'],['账号权限、日志、统计分析','手术排程、设备接口']], [3.25,3.25])

heading('2. 需求分析：功能需求（10 分）')
add_p('功能需求按业务对象与岗位职责划分。前端菜单用于辅助操作，后端接口仍会进行权限、业务状态和数据范围校验。')
table(doc,['模块','主要功能','主要使用角色'],[
 ['基础数据与权限','科室、病区、病房、床位、医疗项目；用户、角色、权限和审计','管理员'],
 ['患者与住院','患者建档；办理入院；分配科室、医生和床位；查看住院历史','住院处'],
 ['床位管理','查询空闲床位；转床/转科；维护床位状态；查看占用历史','住院处'],
 ['医嘱管理','创建、提交、停止、撤销医嘱；查看执行进度','医生'],
 ['医嘱执行','药房发药、护士给药、非药品执行；记录执行人与时间','药房、护士、医生'],
 ['财务与结算','押金登记/冲正；费用查询；欠费预警；结算预检与退款','收费人员'],
 ['统计查询','床位使用率、科室收入构成、药品排行、平均住院天数','住院处、收费人员、管理员']], [1.15,4.0,1.35])
heading('2.1 核心业务规则',2)
bullet('同一患者同一时刻只能有一条有效住院记录；同一床位同一时刻只能被一名患者占用。')
bullet('医嘱必须处于可执行状态才允许执行；相同请求号不能重复产生执行、库存或费用事实。')
bullet('药品遵循“发药 DISPENSE → 给药 ADMINISTER”：发药扣库存并计费，给药不重复收费。')
bullet('押金、费用和库存以流水记录保存；发生错误时生成冲正记录，不物理删除历史。')
bullet('存在未完成医嘱、可用余额不足或已结算时，系统阻止出院结算。')

heading('3. 用例模型与核心流程')
add_p('用例图将外部参与者、系统边界和可见功能放在同一视图中，可用于说明“谁在什么条件下做什么”。')
add_image(usecase, Inches(6.55), '系统用例图：展示管理员、住院处、医生、护士和药房、收费人员与核心业务功能的关系'); cap=add_p('图 1  系统用例图',after=8,align=WD_ALIGN_PARAGRAPH.CENTER); set_run(cap.runs[0],9,False,GRAY)
note('讲解顺序建议：先从角色切入，再强调住院处、医生、护士/药房、收费人员围绕同一份住院记录协作。')
heading('3.1 入院—结算主流程',2)
table(doc,['阶段','输入/操作','系统结果'],[
 ['入院','选择患者、科室、主管医生和可用床位；登记初始押金','创建住院记录、床位使用历史、押金流水；床位变为占用'],
 ['治疗','医生开立并提交医嘱；药房/护士/医生执行','记录执行事实；药品发药时扣库存；成功执行自动生成费用'],
 ['变更','转床或转科','结束原床位历史，创建新历史，更新床位与住院记录'],
 ['结算','申请出院、结算预检、完成结算','汇总有效费用和押金；退款；释放床位；住院状态变更为已出院']], [1.0,3.55,1.95])
heading('3.2 状态约束',2)
table(doc,['对象','关键状态变化','约束'],[
 ['住院记录','住院中 → 待出院 → 已出院','已出院后禁止继续开立、执行医嘱或修改账目'],
 ['医嘱','草稿 → 已提交 → 执行中/已完成；也可停止、撤销','未提交、已停止或已撤销的项目不可执行'],
 ['床位','空闲 ↔ 占用；可维修/停用','目标床位非空闲时，转床事务整体失败'],
 ['账户提醒','开放 → 已解决','余额低于阈值产生提醒；结算后自动关闭']], [1.0,2.7,2.8])

heading('4. 性能及其他非功能需求（5 分）')
table(doc,['类别','需求与设计响应'],[
 ['性能','普通查询目标 1 秒内；统计查询目标 3—5 秒内。对患者证件号、住院状态、床位状态、医嘱与费用时间等高频字段建立索引。'],
 ['可靠性与一致性','入院分床、转床转科、医嘱执行计费、出院结算均作为事务处理；任一环节失败则整体回滚。'],
 ['并发控制','关键操作锁定住院记录与目标床位；床位记录维护版本号，避免并发占床或结算与记账交错。'],
 ['安全','JWT 令牌、BCrypt 密码哈希、参数校验、预编译 SQL、RBAC 权限控制、操作审计。'],
 ['可维护性','前后端分离；数据库脚本按结构、索引、视图、过程/触发器、种子数据组织；核心规则以状态与流水方式表达。'],
 ['数据完整性','外键、唯一约束、金额精确类型 DECIMAL、触发器保护历史事实；基础数据采用软停用。']], [1.35,5.15])
note('性能指标是课程设计的目标值，不等同于生产级压测结论。当前项目已完成后端 45 项、前端 71 项自动化验证，可作为功能正确性的验证依据。','答辩边界')

heading('5. 数据库设计目标与原则（15 分）')
heading('5.1 设计思路',2)
add_p('数据库以“住院记录”为业务中心，但不把所有信息塞入一张表。患者、床位、医嘱、执行、费用、押金和结算分别建模；描述性主数据与发生型流水分离，既满足第三范式，也便于追溯。')
table(doc,['设计原则','体现'],[
 ['主数据与流水分离','患者、床位、医疗项目保存相对稳定的信息；执行、费用、押金、库存、结算保存发生事实。'],
 ['历史不可覆盖','已发生流水禁止直接更新或删除，通过引用原记录的冲正记录修正。'],
 ['关系显式化','多对多关系采用关联表，如用户—角色、角色—权限；住院—床位通过床位使用历史表达。'],
 ['快照保留','费用价格、名称等在医嘱项目/费用记录中保留快照，避免主数据改价影响历史账目。'],
 ['适度冗余','住院记录保留当前床位便于查询，同时以床位使用表保存完整变更历史。']], [1.5,5.0])
heading('5.2 逻辑结构与对象规模',2)
table(doc,['对象类别','代表性表','作用'],[
 ['组织与权限','department、ward、room、bed、staff、user_account、role、permission','组织结构、床位资源与 RBAC'],
 ['患者与住院','patient、admission、bed_usage、transfer_record','患者历次住院、当前状态和床位/科室变更'],
 ['医嘱与执行','medical_order、order_item、execution_record、medical_item、drug_profile','临床意图、具体项目、实际执行与药品属性'],
 ['财务与库存','deposit_transaction、charge_detail、drug_inventory_transaction、account_alert','资金、收费、库存变动与欠费提示'],
 ['结算与审计','settlement、settlement_detail、audit_log','结算汇总、分类明细和操作追踪']], [1.3,3.3,1.9])

heading('6. 核心 ER 图与实体关系说明')
add_p('为保证课堂展示可读性，下图抽取对业务闭环最关键的 15 个实体。完整物理模型还包含用户角色关联、模板、权限、病区/病房等支持实体。')
add_image(er, Inches(6.5), '核心 ER 图：展示患者、住院、床位、医嘱、执行、费用、押金和结算实体及其关系'); cap=add_p('图 2  核心实体关系图（ER 图）',after=8,align=WD_ALIGN_PARAGRAPH.CENTER); set_run(cap.runs[0],9,False,GRAY)
heading('6.1 关系基数解读',2)
table(doc,['关系','基数','业务含义'],[
 ['患者 — 住院记录','1:N','一个患者可以多次住院；每次住院只属于一个患者。'],
 ['住院记录 — 床位使用','1:N','住院期间可能转床，因此保留多段床位使用历史。'],
 ['床位 — 床位使用','1:N','一张床在不同时段可被多次使用；同一时刻由唯一约束保证只能有一个有效占用。'],
 ['住院记录 — 医嘱 — 医嘱项目','1:N — 1:N','一次住院可有多张医嘱；一张医嘱可包含多个具体项目。'],
 ['医嘱项目 — 执行记录','1:N','同一长期项目可多次执行；执行记录保存执行人、时间、数量和环节。'],
 ['执行记录 — 费用明细','1:N','一次执行可产生相应收费事实；成功发药或非药品执行计费。'],
 ['住院记录 — 结算单','0..1:1','一次住院最多对应一张结算单；未结算时可为 0。'],
 ['结算单 — 结算明细','1:N','结算单按药品、检查、治疗等类别汇总费用。']], [1.65,1.05,3.8])

heading('7. 关键实体说明与关系映射')
table(doc,['实体','核心属性/标识','与其他实体的关系'],[
 ['PATIENT 患者','patient_no、identity_number（唯一）','1:N 关联 ADMISSION，解决“一个患者多次住院”。'],
 ['ADMISSION 住院记录','admission_no、status、department_id、current_bed_id','业务主轴；关联床位历史、医嘱、财务、告警和结算。'],
 ['BED_USAGE 床位使用','admission_id、bed_id、start_time、end_time','连接住院与床位，记录每次分床/转床的时间区间。'],
 ['MEDICAL_ORDER 医嘱','admission_id、prescribed_by、status','医生一次开立的医嘱头，包含多个 ORDER_ITEM。'],
 ['ORDER_ITEM 医嘱项目','medical_item_id、dosage、order_type、进度字段','具体到药品/检查/治疗项目，是实际执行的对象。'],
 ['EXECUTION_RECORD 执行记录','order_item_id、executor_id、phase、quantity、request_no','记录真实业务事实；关联费用和药品库存流水。'],
 ['DEPOSIT_TRANSACTION 押金流水','admission_id、amount、transaction_type','记录初缴、补缴、退款与冲正，计算押金余额。'],
 ['CHARGE_DETAIL 费用明细','admission_id、execution_id、amount、原费用引用','记录收费与冲正，形成结算依据。'],
 ['SETTLEMENT / DETAIL 结算','admission_id（唯一）、总额、分类明细','一张结算单汇总本次住院的最终收费、押金和退款。']], [1.65,2.25,2.6])
heading('7.1 多对多与自关联设计',2)
table(doc,['场景','实现方式','原因'],[
 ['用户与角色','user_role(user_id, role_id)','一个用户可拥有多个角色，一个角色可分配给多个用户。'],
 ['角色与权限','role_permission(role_id, permission_id)','避免在角色表中存放可变的权限列表，满足规范化。'],
 ['执行冲正','execution_record.reversed_execution_id 自关联','冲正记录指向原执行，保留完整更正链路。'],
 ['费用/押金冲正','original_charge_id / original_transaction_id 自关联','不修改原流水，以反向金额表达撤销。']], [1.45,2.55,2.5])

heading('8. 演讲重点与总结')
heading('8.1 建议 6—8 分钟讲解顺序',2)
table(doc,['时间','讲解内容','要点'],[
 ['0:00—0:45','问题与范围','小型医院住院闭环；明确不做门诊、医保、线上支付。'],
 ['0:45—2:30','功能需求与用例','六类角色如何协作；强调权限不是只隐藏按钮。'],
 ['2:30—3:30','核心业务规则','床位冲突、重复执行、库存不足、未完成医嘱不能结算。'],
 ['3:30—5:45','ER 图与实体关系','以 ADMISSION 为主线逐步讲患者、床位、医嘱、执行、费用、结算。'],
 ['5:45—6:45','数据库亮点','流水与冲正、事务、约束、索引、状态机。'],
 ['6:45—结束','总结','系统保证一致性、可追溯性、可扩展性，并支持统计。']], [1.0,1.55,3.95])
heading('8.2 结论',2)
add_p('本系统通过以住院记录为中心的关系模型，将患者、床位、医嘱、执行、库存、费用、押金与结算连接为完整业务闭环。数据库设计的核心价值在于：用关系与约束表达现实业务，用流水和冲正保护历史，用事务保证关键业务的一致性。')
note('一句话总结：患者“住进来”产生 Admission；围绕 Admission 发生床位、医嘱、执行和账务；最终通过 Settlement 完成结算并释放床位。','结束语')

doc.core_properties.title='小型医院住院信息管理与费用结算系统—需求分析与设计'
doc.core_properties.subject='数据库课程设计第一次课堂演讲'
doc.core_properties.author='课程设计项目组'
doc.save(OUT)
print(OUT)
