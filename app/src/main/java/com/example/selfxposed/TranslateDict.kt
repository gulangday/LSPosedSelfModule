package com.example.selfxposed

import android.content.Context
import java.io.File

/**
 * 界面翻译用的词库。
 *
 * 纯离线：只查本地对照表，不联网。翻不到就保持英文原样，不硬翻，
 * 这样既不会闪（不用等服务器），也不会把用户名字、网址翻坏。
 *
 * 数据落点（沿用 AGENTS.md §3）：
 * - 用户自己加的词条 → 目标应用 `files/selfxposed_translate_user.conf`，每行 `英文=中文`
 * - 内置词库 → 直接编译进代码，不占存储
 *
 * hook 与目标应用同进程，直接读自己目录；模块 UI 通过 root / Shizuku 读写。
 */
object TranslateDict {
    const val USER_FILE = "selfxposed_translate_user.conf"

    /** 翻译选项（按应用）：带符号是否跳过 等。 */
    const val CONFIG_FILE = "selfxposed_translate.conf"

    /** 网址、邮箱、版本号这类不翻。 */
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val VERSION = Regex("^[vV]?\\d+(\\.\\d+)+.*$")

    /**
     * 危险符号：这些字符出现在字符串里就整串跳过。
     *
     * 原因：`%s` `%d` 是格式化占位符，`{0}` `{}` 是模板占位符，
     * `<b>` 是标记，`&amp;` 是转义，`$` 是变量——这些被翻掉会直接让程序出错甚至闪退。
     * 普通标点（逗号、句号、冒号、问号、连字符、括号）不算，那是正常句子的一部分。
     */
    private const val RISKY_SYMBOLS = "%{}<>&=\\$#@|~^`"

    /** 字符串里有没有危险符号。 */
    fun hasRiskySymbols(text: String): Boolean = text.any { it in RISKY_SYMBOLS }

    /** 拆词用的单词规则：字母开头，允许中间的撇号（don't、what's）。 */
    private val WORD = Regex("[A-Za-z][A-Za-z']*")

    /** 内置词库：英文（小写）= 中文。整句优先，翻译时按整串匹配。 */
    private val BUILTIN_RAW = """
ok=确定
okay=好的
cancel=取消
save=保存
save changes=保存修改
save as=另存为
delete=删除
remove=移除
edit=编辑
done=完成
close=关闭
close all=全部关闭
back=返回
next=下一步
previous=上一步
continue=继续
continue anyway=仍然继续
skip=跳过
skip for now=暂时跳过
retry=重试
refresh=刷新
reload=重新加载
search=搜索
share=分享
share app=分享应用
copy=复制
copy link=复制链接
paste=粘贴
cut=剪切
select=选择
select all=全选
undo=撤销
redo=重做
add=添加
clear=清空
clear cache=清除缓存
clear data=清除数据
reset=重置
apply=应用
confirm=确认
submit=提交
send=发送
download=下载
upload=上传
install=安装
uninstall=卸载
uninstall app=卸载应用
update=更新
upgrade=升级
open=打开
open with=用其他应用打开
open in browser=在浏览器打开
exit=退出
quit=退出
login=登录
log in=登录
logout=退出登录
log out=退出登录
sign in=登录
sign up=注册
register=注册
settings=设置
preferences=偏好设置
options=选项
help=帮助
about=关于
feedback=反馈
contact us=联系我们
privacy policy=隐私政策
terms of service=服务条款
terms and conditions=条款与条件
license=许可协议
version=版本
app version=应用版本
build number=版本号
check for updates=检查更新
rate us=给我们评分
more=更多
less=更少
all=全部
none=无
yes=是
no=否
enable=开启
disable=关闭
enabled=已开启
disabled=已关闭
on=开
off=关
start=开始
stop=停止
pause=暂停
resume=继续
play=播放
mute=静音
unmute=取消静音
volume=音量
brightness=亮度
notification=通知
notifications=通知
sound=声音
vibration=振动
ringtone=铃声
display=显示
theme=主题
dark mode=深色模式
light mode=浅色模式
auto=自动
language=语言
region=地区
storage=存储
memory=内存
battery=电池
network=网络
wifi=无线网络
wi-fi=无线网络
bluetooth=蓝牙
mobile data=移动数据
airplane mode=飞行模式
location=位置
camera=相机
microphone=麦克风
permission=权限
permissions=权限
allow=允许
deny=拒绝
always allow=始终允许
never=从不
ask every time=每次询问
security=安全
password=密码
change password=修改密码
forgot password=忘记密码
username=用户名
email=邮箱
phone number=手机号
verify=验证
verification=验证
verification code=验证码
account=账户
profile=个人资料
my account=我的账户
personal information=个人信息
birthday=生日
gender=性别
male=男
female=女
other=其他
discard=放弃
unsaved changes=未保存的修改
loading=加载中
please wait=请稍候
processing=处理中
success=成功
failed=失败
error=错误
warning=警告
notice=提示
tip=小提示
are you sure=确定吗
this action cannot be undone=此操作无法撤销
no results=没有结果
nothing here=这里什么都没有
empty=空
no data=暂无数据
try again=重试
try later=稍后再试
network error=网络错误
check your connection=请检查网络连接
server error=服务器错误
timeout=超时
file not found=找不到文件
access denied=拒绝访问
permission required=需要权限
grant permission=授予权限
not supported=不支持
coming soon=即将推出
featured=精选
recommended=推荐
popular=热门
new=新
hot=热门
latest=最新
trending=趋势
history=历史
favorites=收藏
bookmark=书签
saved=已保存
recent=最近
category=分类
categories=分类
home=首页
discover=发现
explore=探索
search results=搜索结果
filter=筛选
sort=排序
sort by=排序方式
view=查看
list view=列表视图
grid view=网格视图
details=详情
information=信息
description=描述
title=标题
name=名称
size=大小
date=日期
time=时间
type=类型
status=状态
progress=进度
completed=已完成
in progress=进行中
pending=待处理
cancelled=已取消
expired=已过期
available=可用
unavailable=不可用
free=免费
paid=付费
price=价格
total=合计
subtotal=小计
discount=折扣
coupon=优惠券
promo code=优惠码
checkout=结算
cart=购物车
shopping cart=购物车
buy now=立即购买
add to cart=加入购物车
order=订单
orders=订单
my orders=我的订单
payment=支付
pay now=立即支付
payment method=支付方式
credit card=信用卡
balance=余额
wallet=钱包
recharge=充值
withdraw=提现
transfer=转账
transaction=交易
invoice=发票
receipt=收据
refund=退款
subscribe=订阅
subscription=订阅
unsubscribe=取消订阅
trial=试用
free trial=免费试用
premium=高级版
vip=会员
member=会员
membership=会员
restore purchase=恢复购买
purchase=购买
purchased=已购买
downloading=下载中
uploading=上传中
syncing=同步中
synced=已同步
sync failed=同步失败
backup=备份
restore=恢复
import=导入
export=导出
selection=选择
play again=重新播放
video=视频
audio=音频
photo=照片
image=图片
gallery=相册
album=相册
music=音乐
song=歌曲
playlist=播放列表
artist=歌手
duration=时长
quality=画质
resolution=分辨率
full screen=全屏
exit full screen=退出全屏
rotate=旋转
zoom in=放大
zoom out=缩小
captions=字幕
subtitles=字幕
speed=倍速
loop=循环
shuffle=随机播放
repeat=重复
next track=下一首
previous track=上一首
now playing=正在播放
queue=播放队列
load more=加载更多
see all=查看全部
see more=查看更多
show more=展开更多
show less=收起
expand=展开
collapse=收起
menu=菜单
drawer=侧边栏
tab=标签页
back to top=回到顶部
scroll=滚动
swipe=滑动
tap=点按
double tap=双击
long press=长按
drag=拖动
welcome=欢迎
hello=你好
good morning=早上好
good afternoon=下午好
good evening=晚上好
good night=晚安
thank you=谢谢
thanks=谢谢
please=请
sorry=抱歉
congratulations=恭喜
get started=开始使用
remind me later=稍后提醒
not now=现在不要
maybe later=稍后再说
later=稍后
now=现在
today=今天
yesterday=昨天
tomorrow=明天
this week=本周
this month=本月
this year=今年
last week=上周
last month=上月
last year=去年
minute=分钟
minutes=分钟
hour=小时
hours=小时
day=天
days=天
week=周
weeks=周
month=月
months=月
year=年
years=年
second=秒
seconds=秒
just now=刚刚
remaining=剩余
time left=剩余时间
connection lost=连接已断开
reconnecting=正在重新连接
offline=离线
online=在线
connected=已连接
disconnected=未连接
connecting=正在连接
operation failed=操作失败
open settings=打开设置
go to settings=前往设置
system settings=系统设置
app settings=应用设置
default=默认
set as default=设为默认
cache=缓存
data usage=流量使用
app info=应用信息
force stop=强制停止
device info=设备信息
device name=设备名称
system version=系统版本
android version=安卓版本
software update=软件更新
system update=系统更新
restart=重启
reboot=重启
shut down=关机
power off=关机
lock screen=锁屏
unlock=解锁
fingerprint=指纹
face unlock=面部解锁
screen lock=屏幕锁定
do not disturb=免打扰
silent mode=静音模式
vibrate=振动
ring=响铃
alarm=闹钟
timer=计时器
stopwatch=秒表
calendar=日历
event=事件
reminder=提醒
note=笔记
notes=笔记
task=任务
tasks=任务
add task=添加任务
delete task=删除任务
edit task=编辑任务
due date=截止日期
priority=优先级
high=高
medium=中
low=低
tag=标签
tags=标签
label=标签
labels=标签
comment=评论
comments=评论
reply=回复
replies=回复
like=赞
dislike=不喜欢
follow=关注
following=已关注
followers=粉丝
unfollow=取消关注
block=屏蔽
unblock=取消屏蔽
report=举报
message=消息
messages=消息
chat=聊天
send message=发送消息
type a message=输入消息
attach=附件
attachment=附件
emoji=表情
sticker=贴纸
voice message=语音消息
video call=视频通话
voice call=语音通话
call=通话
hang up=挂断
missed call=未接来电
incoming call=来电
outgoing call=去电
contacts=联系人
contact=联系人
add contact=添加联系人
phonebook=通讯录
dial=拨号
keypad=拨号键盘
speaker=扬声器
screen share=屏幕共享
recording=录制中
record=录制
stop recording=停止录制
screenshot=截屏
screen record=录屏
print=打印
scan=扫描
document=文档
documents=文档
file=文件
files=文件
folder=文件夹
folders=文件夹
path=路径
rename=重命名
move=移动
compress=压缩
extract=解压
properties=属性
choose app=选择应用
always=始终
just once=仅此一次
remember my choice=记住我的选择
terms=条款
agreement=协议
accept=接受
decline=拒绝
agree=同意
disagree=不同意
optional=可选
required=必填
don't show again=不再显示
show again=再次显示
learn more=了解更多
read more=阅读更多
read less=收起
support=支持
customer service=客服
report a problem=报告问题
send feedback=发送反馈
bug report=问题反馈
crash report=崩溃报告
anonymous=匿名
analytics=数据统计
ads=广告
advertisement=广告
personalized ads=个性化广告
ad-free=免广告
remove ads=移除广告
pro version=专业版
lite version=精简版
beta=测试版
stable=稳定版
preview=预览
experimental=实验性
new features=新功能
what's new=新变化
release notes=更新说明
changelog=更新日志
last updated=最近更新
updated=已更新
installed=已安装
not installed=未安装
installing=正在安装
uninstalling=正在卸载
waiting=等待中
queued=排队中
download failed=下载失败
download complete=下载完成
pause download=暂停下载
resume download=继续下载
cancel download=取消下载
provider=提供方
providers=提供方
api=API
api key=API 密钥
api keys=API 密钥
key=密钥
keys=密钥
model=模型
models=模型
agent=智能体
agents=智能体
gateway=网关
prompt=提示词
prompts=提示词
token=令牌
tokens=令牌
endpoint=接口地址
endpoints=接口地址
backend=后端
frontend=前端
request=请求
requests=请求
response=响应
header=请求头
headers=请求头
server=服务器
host=主机
port=端口
url=链接
uri=链接
sdk=SDK
plugin=插件
plugins=插件
extension=扩展
extensions=扩展
module=模块
modules=模块
package=包
repository=仓库
branch=分支
commit=提交
merge=合并
diff=差异
patch=补丁
build=构建
rebuild=重新构建
compile=编译
execute=执行
run=运行
running=运行中
debug=调试
log=日志
logs=日志
console=控制台
terminal=终端
output=输出
input=输入
exception=异常
crash=崩溃
stack=堆栈
trace=追踪
mock=模拟
stub=桩
cache=缓存
session=会话
sessions=会话
expire=过期
granted=已授予
denied=已拒绝
access=访问
revoke=撤销
grant=授予
valid=有效
invalid=无效
unknown=未知
missing=缺失
broken=已损坏
ready=就绪
busy=忙碌
idle=空闲
test=测试
testing=测试中
check=检查
validate=校验
connect=连接
disconnect=断开
reconnect=重新连接
receive=接收
change=更改
choose=选择
pick=选择
create=创建
modify=修改
end=结束
begin=开始
show=显示
hide=隐藏
click=点击
press=按下
hold=按住
button=按钮
field=字段
form=表单
checkbox=复选框
toggle=开关
slider=滑块
window=窗口
dialog=对话框
popup=弹窗
toolbar=工具栏
sidebar=侧边栏
page=页面
pages=页面
next page=下一页
previous page=上一页
first page=首页
last page=最后一页
row=行
column=列
table=表格
chart=图表
graph=图表
count=数量
amount=金额
number=数字
value=值
values=值
text=文本
content=内容
subtitle=副标题
footer=页脚
address=地址
city=城市
country=国家
postcode=邮编
zip code=邮编
full name=全名
first name=名
last name=姓
nickname=昵称
user=用户
users=用户
dashboard=仪表盘
overview=概览
summary=摘要
report=报告
reports=报告
analytics=数据分析
statistics=统计
settings=设置
general=通用
appearance=外观
behavior=行为
performance=性能
advanced=高级
basic=基础
custom=自定义
developer=开发者
developer options=开发者选项
release=发布
build number=内部版本号
alpha=内测版
important=重要
attention=注意
caution=小心
example=示例
tips=小提示
note=注意
is=是
are=是
was=是
were=是
be=是
not=不
only=仅
also=也
must=必须
should=应该
can=可以
cannot=不能
will=将
would=会
if=如果
then=然后
else=否则
when=当
which=哪个
that=该
this=此
these=这些
those=那些
your=你的
you=你
our=我们的
we=我们
its=它的
it=它
and=和
or=或
of=的
with=用
to=到
for=用于
by=由
from=从
use=使用
used=使用
using=使用
set=设置
get=获取
need=需要
needs=需要
able=可以
unable=无法
before=之前
after=之后
during=期间
most=最多
some=一些
any=任何
each=每个
every=每个
here=这里
there=那里
where=哪里
what=什么
how=如何
why=为什么
""".trimIndent()

    private val builtin: Map<String, String> by lazy {
        BUILTIN_RAW.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) {
                    null
                } else {
                    val key = line.substring(0, index).trim().lowercase()
                    val value = line.substring(index + 1).trim()
                    if (key.isEmpty() || value.isEmpty()) null else key to value
                }
            }
            .toMap()
    }

    fun builtinCount(): Int = builtin.size

    /** 解析 `英文=中文` 文本。 */
    fun parse(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) {
                    null
                } else {
                    val key = line.substring(0, index).trim().lowercase()
                    val value = line.substring(index + 1).trim()
                    if (key.isEmpty() || value.isEmpty()) null else key to value
                }
            }
            .toMap()
    }

    // ---- hook 侧 ----

    fun loadLocalUser(context: Context): Map<String, String> = try {
        val file = File(context.filesDir, USER_FILE)
        if (!file.exists()) emptyMap() else parse(file.readText())
    } catch (_: Throwable) {
        emptyMap()
    }

    /** 内置 + 用户词条；用户词条优先（同名覆盖内置）。 */
    fun loadLocal(context: Context): Map<String, String> = builtin + loadLocalUser(context)

    fun saveLocalUser(context: Context, entries: Map<String, String>) {
        try {
            val file = File(context.filesDir, USER_FILE)
            if (entries.isEmpty()) {
                file.delete()
            } else {
                file.writeText(entries.entries.joinToString("\n") { it.key + "=" + it.value })
            }
        } catch (_: Throwable) {
        }
    }

    // ---- UI 侧（走 root / Shizuku） ----

    fun readRemoteUser(pkg: String): Map<String, String> = try {
        val raw = RootShell.exec("cat /data/data/$pkg/files/$USER_FILE 2>/dev/null", 6_000L)
        parse(raw)
    } catch (_: Throwable) {
        emptyMap()
    }

    fun writeRemoteUser(pkg: String, entries: Map<String, String>): Boolean = try {
        val base = "/data/data/$pkg/files"
        if (entries.isEmpty()) {
            val out = RootShell.exec("rm -f $base/$USER_FILE 2>/dev/null; echo ok", 6_000L)
            out.contains("ok")
        } else {
            val content = entries.entries.joinToString("\n") { it.key + "=" + it.value }
            val escaped = content.replace("'", "'\\''")
            val out = RootShell.exec(
                "mkdir -p $base; printf '%s\\n' '$escaped' > $base/$USER_FILE; " +
                    "chown $(stat -c %u:%g /data/data/$pkg) $base/$USER_FILE; " +
                    "chmod 660 $base/$USER_FILE; echo ok",
                6_000L
            )
            out.contains("ok")
        }
    } catch (_: Throwable) {
        false
    }

    // ---- 翻译核心 ----

    /** 未命中的缓存储存值：译文不会是空串，用哨兵区分「没翻到」和「没查过」。 */
    private const val MISS = "\u0000"

    private const val TRANSLATE_CACHE_MAX = 512

    /** 原文→译文（或未命中）的有界缓存，按访问顺序淘汰。 */
    private val translateCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > TRANSLATE_CACHE_MAX
    }

    /** 缓存对应的词库对象：词库一换就整体作废，避免旧译文串到新词库上。 */
    private var cacheDict: Map<String, String>? = null

    /**
     * 这个字符串要不要跳过（不翻）。
     * 网址、邮箱、版本号、纯数字、没有英文字母的，都不翻——
     * 它们翻错了比不翻更糟（比如把用户名、电话号码翻成中文）。
     */
    fun shouldSkip(text: String, skipSymbols: Boolean = true): Boolean {
        val value = text.trim()
        if (value.isEmpty()) return true
        // 上限放到 400：界面里的说明段落经常有一两百个字符，
        // 卡在 120 会让整段英文原样留着。太长的（日志、协议全文）仍然跳过。
        if (value.length > 400) return true
        if (value.contains("http://") || value.contains("https://") || value.contains("www.")) return true
        // 一个英文字母都没有（纯中文/纯数字/纯符号）就不用往下跑了
        if (value.none { it in 'a'..'z' || it in 'A'..'Z' }) return true
        // 带危险符号的整串不碰：格式串、模板串、标记被翻掉会让程序出错
        if (skipSymbols && hasRiskySymbols(value)) return true
        if (EMAIL.containsMatchIn(value)) return true
        if (VERSION.matches(value)) return true
        return false
    }

    /**
     * 翻译一个字符串。
     *
     * 分两步：
     * 1. 整串（去掉结尾标点后）先查表，命中就直接用——「Settings:」能翻成「设置：」；
     * 2. 整串没命中，再把句子拆成词，按「长的词组优先」一段段替换。
     *    这样长句子也能翻出大半，而不是整句放弃。
     *
     * 一个词都没翻到就返回 null，保持原样不动。
     */
    fun translate(
        text: String,
        dict: Map<String, String>,
        skipSymbols: Boolean = true
    ): String? {
        val raw = text.trim()
        if (raw.isEmpty()) return null
        // 同一批界面文字会在多轮扫描里反复进来，命中缓存就不用重跑正则和拆词
        val key = (if (skipSymbols) "1" else "0") + raw
        synchronized(translateCache) {
            if (cacheDict !== dict) {
                translateCache.clear()
                cacheDict = dict
            }
            translateCache[key]?.let { return if (it == MISS) null else it }
        }
        val result = if (shouldSkip(raw, skipSymbols)) {
            null
        } else {
            exactTranslate(raw, dict) ?: wordTranslate(raw, dict)
        }
        synchronized(translateCache) {
            // 计算期间词库被换掉就不要写回去，否则会把旧译文挂在新的缓存身份下
            if (cacheDict === dict) translateCache[key] = result ?: MISS
        }
        return result
    }

    /**
     * 整串匹配。结尾的冒号、问号、感叹号、省略号会先剥掉再查，查到后补回去，
     * 这样「Settings:」也能命中「settings」。
     */
    private fun exactTranslate(raw: String, dict: Map<String, String>): String? {
        var suffix = ""
        var core = raw
        while (core.isNotEmpty()) {
            val last = core.last()
            if (last == ':' || last == '：' || last == '?' || last == '？' ||
                last == '!' || last == '！' || last == '…'
            ) {
                suffix = last + suffix
                core = core.dropLast(1)
            } else {
                break
            }
        }
        // 结尾一个句点也剥一次（注意别把 1.2.3 这类剥坏，版本号在上面已跳过）
        if (core.endsWith(".") && core.length > 1) {
            suffix = "." + suffix
            core = core.dropLast(1)
        }

        val key = core.trim().lowercase()
        if (key.isEmpty()) return null
        val hit = dict[key] ?: return null
        if (hit == core.trim()) return null
        return hit + suffix
    }

    /** 一次最多把几个词拼起来查（词组优先，例如 api key、check for updates）。 */
    private const val MAX_WORDS = 4

    /**
     * 拆词翻译。
     *
     * 只在「字符串里有空格」时才走这里——单个词（可能是包名、变量名、标识符）
     * 只允许整串精确命中，避免把 `api_key`、`com.foo.bar` 这种改坏。
     * 词与词之间必须只隔空白才会合并成词组，跨标点不硬凑。
     */
    private fun wordTranslate(text: String, dict: Map<String, String>): String? {
        if (text.none { it == ' ' || it == '\t' || it == '\n' }) return null
        val matches = try {
            WORD.findAll(text).toList()
        } catch (_: Throwable) {
            return null
        }
        if (matches.isEmpty()) return null

        val out = StringBuilder(text.length + 16)
        var cursor = 0
        var index = 0
        var translatedAny = false

        while (index < matches.size) {
            var consumed = 0
            var replacement: String? = null
            val maxSpan = minOf(MAX_WORDS, matches.size - index)
            for (span in maxSpan downTo 1) {
                // 只有当这几个词之间全是空白时，才允许拼成一个词组
                var joinable = true
                for (j in index until index + span - 1) {
                    val gap = text.substring(matches[j].range.last + 1, matches[j + 1].range.first)
                    if (gap.isEmpty() || gap.isNotBlank()) {
                        joinable = false
                        break
                    }
                }
                if (!joinable) continue
                val start = matches[index].range.first
                val end = matches[index + span - 1].range.last
                val phrase = text.substring(start, end + 1).lowercase()
                val hit = dict[phrase] ?: continue
                replacement = hit
                consumed = span
                break
            }

            if (replacement != null) {
                out.append(text, cursor, matches[index].range.first)
                out.append(replacement)
                cursor = matches[index + consumed - 1].range.last + 1
                index += consumed
                translatedAny = true
            } else {
                index++
            }
        }

        if (!translatedAny) return null
        out.append(text, cursor, text.length)
        val result = out.toString()
        return if (result == text) null else result
    }

    // ---- 翻译选项（按应用） ----

    /**
     * 是否跳过带危险符号的字符串。默认 true（保安全）。
     *
     * 理由：格式串（`%d 项`）、模板串（`{0}`）、标记（`<b>`）被翻掉会让目标应用
     * 出错甚至闪退。关掉能多翻一些，但风险自负。
     */
    fun parseOptions(raw: String): Boolean {
        if (raw.isBlank()) return true
        return raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) {
                    null
                } else {
                    line.substring(0, index).trim() to line.substring(index + 1).trim()
                }
            }
            .lastOrNull { it.first == KEY_SKIP_SYMBOLS }
            ?.let { it.second != "0" }
            ?: true
    }

    private const val KEY_SKIP_SYMBOLS = "skipSymbols"

    fun optionsText(skipSymbols: Boolean): String =
        "# 翻译选项\n$KEY_SKIP_SYMBOLS=" + (if (skipSymbols) "1" else "0")

    // ---- hook 侧 ----

    fun loadLocalSkipSymbols(context: Context): Boolean = try {
        val file = File(context.filesDir, CONFIG_FILE)
        if (!file.exists()) true else parseOptions(file.readText())
    } catch (_: Throwable) {
        true
    }

    // ---- UI 侧（走 root / Shizuku） ----

    fun readRemoteSkipSymbols(pkg: String): Boolean = try {
        val raw = RootShell.exec("cat /data/data/$pkg/files/$CONFIG_FILE 2>/dev/null", 6_000L)
        parseOptions(raw)
    } catch (_: Throwable) {
        true
    }

    fun writeRemoteOptions(pkg: String, skipSymbols: Boolean): Boolean = try {
        val base = "/data/data/$pkg/files"
        val content = optionsText(skipSymbols).replace("'", "'\\''")
        val out = RootShell.exec(
            "mkdir -p $base; printf '%s\\n' '$content' > $base/$CONFIG_FILE; " +
                "chown $(stat -c %u:%g /data/data/$pkg) $base/$CONFIG_FILE; " +
                "chmod 660 $base/$CONFIG_FILE; echo ok",
            6_000L
        )
        out.contains("ok")
    } catch (_: Throwable) {
        false
    }

    // ---- 导入 / 导出 ----

    fun exportText(user: Map<String, String>): String = buildString {
        append("# 自用模块 · 翻译词库\n")
        append("# 每行一条：英文=中文（英文不区分大小写）\n")
        append("# 内置词库 ${builtin.size} 条已随应用打包，这里只需要写你自己加的\n")
        user.entries.sortedBy { it.key }.forEach { entry ->
            append(entry.key)
            append('=')
            append(entry.value)
            append('\n')
        }
    }
}
