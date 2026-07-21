package top.azek431.hzzs

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * 进程级 Application 入口，仅负责启用 Hilt 依赖注入图。
 *
 * 不承载业务状态或 UI 导航；业务单例由 Hilt 模块在需要时创建。
 */
@HiltAndroidApp
class HzzsApplication : Application()
