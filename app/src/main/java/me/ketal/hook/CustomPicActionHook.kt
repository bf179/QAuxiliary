/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2025 QAuxiliary developers
 * https://github.com/cinit/QAuxiliary
 *
 * This software is an opensource software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version as published
 * by QAuxiliary contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see
 * <https://www.gnu.org/licenses/>.
 */

package me.ketal.hook

import android.content.Context
import android.view.View
import cc.hicore.QApp.QAppUtils
import cc.ioctl.util.Reflex
import com.github.kyuubiran.ezxhelper.utils.Log
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.findMethodOrNull
import com.github.kyuubiran.ezxhelper.utils.tryOrLogFalse
import com.xiaoniu.dispatcher.OnMenuBuilder
import com.xiaoniu.util.ContextUtils
import io.github.qauxv.util.xpcompat.XC_MethodHook
import io.github.qauxv.R
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.CustomMenu
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.Initiator._ChatMessage
import io.github.qauxv.util.Initiator._MarketFaceItemBuilder
import io.github.qauxv.util.Initiator._MixedMsgItemBuilder
import io.github.qauxv.util.Initiator._PicItemBuilder
import io.github.qauxv.util.SyncUtils
import io.github.qauxv.util.Toasts
import io.github.qauxv.util.dexkit.AbstractQQCustomMenuItem
import xyz.nextalone.util.get
import xyz.nextalone.util.hookAfter
import xyz.nextalone.util.hookBefore
import xyz.nextalone.util.invoke
import java.io.File
import kotlin.concurrent.thread

/**
 * 自定义图片操作 Hook
 * 在聊天图片的长按菜单中添加自定义按钮，点击后对图片进行特定处理。
 *
 * 修改说明：
 *   - 修改 [name] 和 [description] 可改变设置中显示的名称和描述
 *   - 修改 [MENU_ITEM_TITLE] 可改变长按菜单项的显示文字
 *   - 修改 [processImage] 方法可替换为你自己的图片处理逻辑
 */
@Suppress("UNCHECKED_CAST")
@FunctionHookEntry
@UiItemAgentEntry
object CustomPicActionHook : CommonSwitchFunctionHook(
    arrayOf(AbstractQQCustomMenuItem)
), OnMenuBuilder {

    /** 长按菜单中显示的文字 */
    private const val MENU_ITEM_TITLE = "自定义处理图片"

    override val name: String = MENU_ITEM_TITLE

    override val description: String =
        "在聊天图片的长按菜单中添加\"$MENU_ITEM_TITLE\"按钮，点击后对图片进行自定义处理"

    override val uiItemLocation: Array<String> = FunctionEntryRouter.Locations.Auxiliary.MESSAGE_CATEGORY

    override fun initOnce() = tryOrLogFalse {
        if (QAppUtils.isQQnt()) {
            // QQ NT 版本由 MenuBuilderHook 统一调度 onGetMenuNt，此处无需额外 Hook
            return@tryOrLogFalse
        }

        // ----------- 旧版 QQ 的处理逻辑：Hook PicItemBuilder 等 -----------
        val clsPicItemBuilder = _PicItemBuilder()
        val targetClasses = listOfNotNull(
            clsPicItemBuilder,
            clsPicItemBuilder?.superclass,
            _MixedMsgItemBuilder(),
            runCatching { Initiator.loadClass("com.tencent.mobileqq.activity.aio.item.StructingMsgItemBuilder") }.getOrNull(),
            _MarketFaceItemBuilder()
        )
        targetClasses.forEach { clazz ->
            // 1) Hook 菜单项点击回调 (id 匹配方式)
            clazz.findMethod {
                name == "a" && parameterTypes.contentEquals(
                    arrayOf(Int::class.javaPrimitiveType, Context::class.java, _ChatMessage())
                )
            }.hookBefore(this@CustomPicActionHook) { m ->
                val (id, context, chatMessage) = m.args
                context as Context
                if (id != R.id.item_custom_pic_action) return@hookBefore
                m.result = null
                val paths = getPicPath(chatMessage)
                if (paths.isEmpty()) {
                    Toasts.error(context, "未找到图片文件")
                    return@hookBefore
                }
                onMenuItemClicked(context, paths)
            }

            // 2) Hook 菜单项生成，把自定义菜单项追加到数组中
            clazz.findMethodOrNull {
                returnType.isArray && parameterTypes.contentEquals(arrayOf(View::class.java))
            }?.hookAfter(this@CustomPicActionHook) { param ->
                val view = param.args[0] as View
                val message = getMessageFromView(view)
                val paths = getPicPath(message)
                if (paths.isEmpty()) return@hookAfter

                param.result = param.result.run {
                    this as Array<Any>
                    val componentType = javaClass.componentType
                    val customItem = CustomMenu.createItem(
                        componentType,
                        R.id.item_custom_pic_action,
                        MENU_ITEM_TITLE,
                        R.drawable.ic_item_tool_72dp
                    )
                    plus(customItem)
                }
            }
        }
    }

    // ================================================================
    //  QQ NT 版本：由 MenuBuilderHook 的 decorators 机制回调 onGetMenuNt
    // ================================================================
    override val targetComponentTypes: Array<String> = arrayOf(
        "com.tencent.mobileqq.aio.msglist.holder.component.pic.AIOPicContentComponent"
    )

    override fun onGetMenuNt(
        msg: Any,
        componentType: String,
        param: XC_MethodHook.MethodHookParam
    ) {
        if (!isEnabled) return
        val list = param.result as MutableList<Any>
        val activity = ContextUtils.getCurrentActivity() ?: return
        val item = CustomMenu.createItemIconNt(
            msg = msg,
            text = MENU_ITEM_TITLE,
            icon = R.drawable.ic_item_tool_72dp,
            id = R.id.item_custom_pic_action,
            click = {
                runCatching {
                    val path = getFilePathNt(msg)
                    if (path.isNullOrBlank()) {
                        Toasts.error(activity, "未找到图片文件")
                        return@createItemIconNt
                    }
                    onMenuItemClicked(activity, arrayOf(path))
                }.onFailure { t ->
                    Log.e(t)
                    Toasts.error(activity, "处理失败: ${t.message}")
                }
            }
        )
        list.add(item)
    }

    // ================================================================
    //  通用：菜单点击入口 → 调用处理逻辑
    // ================================================================
    private fun onMenuItemClicked(context: Context, imagePaths: Array<String>) {
        // 目前只处理第一张图，需要处理多图可在此循环
        val firstImage = imagePaths.firstOrNull() ?: run {
            Toasts.error(context, "未找到图片文件")
            return
        }
        val file = File(firstImage)
        if (!file.exists()) {
            Toasts.error(context, "请先查看原图后再操作")
            return
        }
        // 在后台线程处理图片，避免阻塞 UI
        thread {
            runCatching {
                processImage(context, file)
            }.onSuccess { handled ->
                if (handled) {
                    SyncUtils.runOnUiThread {
                        Toasts.success(context, "处理完成")
                    }
                }
            }.onFailure { t ->
                Log.e(t)
                SyncUtils.runOnUiThread {
                    Toasts.error(context, "处理失败: ${t.message}")
                }
            }
        }
    }

    // ================================================================
    //  TODO: 在此处实现你自己的图片处理逻辑
    //  返回 true 表示处理成功，会显示"处理完成"提示
    // ================================================================
    private fun processImage(context: Context, imageFile: File): Boolean {
        // ---------- 示例：你可以把这里的代码替换成你自己的处理逻辑 ----------

        // 示例 1: 打印图片信息 (可在 LSPosed / LSPlant 日志中查看)
        Log.i("CustomPicAction: 图片路径=${imageFile.absolutePath}")
        Log.i("CustomPicAction: 图片大小=${imageFile.length()} bytes")

        // 示例 2: 复制到指定目录（例如 QAuxiliary 自定义目录）
        // val destDir = File(Environment.getExternalStorageDirectory(), "QAuxiliary/Images")
        // destDir.mkdirs()
        // val destFile = File(destDir, imageFile.name)
        // imageFile.copyTo(destFile, overwrite = true)
        // context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(destFile)))

        // 示例 3: 调起系统分享
        // val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", imageFile)
        // val intent = Intent(Intent.ACTION_SEND).apply {
        //     type = "image/*"
        //     putExtra(Intent.EXTRA_STREAM, uri)
        //     addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // }
        // context.startActivity(Intent.createChooser(intent, MENU_ITEM_TITLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        // 示例 4: 使用 Bitmap 对图片进行压缩/加滤镜/加水印
        // val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
        // val processed = yourCustomBitmapProcessing(bitmap)
        // saveBitmapToFile(processed, imageFile)

        // 默认返回 true（已处理）。若你的逻辑需要区分成功/失败，可改为返回条件判断
        return true
    }

    // ================================================================
    //  以下为辅助方法：从 ChatMessage / View / NT AIOMsgItem 中获取图片路径
    // ================================================================

    /** 旧版 QQ：根据 ChatMessage 对象获取图片文件路径列表 */
    private fun getPicPath(message: Any): Array<String> {
        return when (Reflex.getShortClassName(message)) {
            "MessageForPic" -> arrayOf(getFilePath(message))
            "MessageForLongMsg" -> {
                val list = message.get("longMsgFragmentList") as? List<Any> ?: emptyList()
                list.filter { Reflex.getShortClassName(it) == "MessageForPic" }
                    .map { getFilePath(it) }
                    .toTypedArray()
            }
            "MessageForMixedMsg" -> {
                val list = message.get("msgElemList") as? List<Any> ?: emptyList()
                list.filter { Reflex.getShortClassName(it) == "MessageForPic" }
                    .map { getFilePath(it) }
                    .toTypedArray()
            }
            "MessageForStructing" -> emptyArray()
            else -> emptyArray()
        }
    }

    /** 旧版 QQ：通过 getFilePath 接口拿到图片本地路径 */
    private fun getFilePath(message: Any): String {
        return arrayOf("chatraw", "chatimg", "chatthumb").map { str ->
            @Suppress("UNCHECKED_CAST")
            message.invoke("getFilePath", str, String::class.java) as String
        }.first { path ->
            File(path).exists()
        }
    }

    /** 旧版 QQ：从图片 View 向上递归找到 ChatMessage */
    private tailrec fun getMessageFromView(view: View): Any {
        return if (view.parent.javaClass.simpleName.endsWith("ListView")) {
            val viewHolder = view.tag
            viewHolder.get(_ChatMessage())!!
        } else getMessageFromView(view.parent as View)
    }

    /** QQ NT 版：获取图片本地路径 */
    private fun getFilePathNt(msg: Any): String? {
        val msgClass = Initiator.loadClass("com.tencent.mobileqq.aio.msg.AIOMsgItem")
        val apiClass = Initiator.loadClass("com.tencent.qqnt.aio.msg.api.impl.AIOMsgItemApiImpl") ?: return null
        val result = apiClass.getDeclaredConstructor().newInstance()
            .invoke("getLocalPath", msg, msgClass)
        return result as? String
    }
}
