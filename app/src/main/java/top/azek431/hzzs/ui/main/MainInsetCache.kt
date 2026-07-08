// 火崽崽助手（HZZS）主页 Padding 初始值缓存器。
//
// 职责：
// - 缓存所有 View 的初始 padding 值
// - 在系统栏安全区域处理时，基于初始 padding 叠加 insets
//
// 不负责：
// - 不处理 insets 应用逻辑（由 MainInsetsController 处理）
// - 不缓存 View 引用（由 MainViewCache 处理）
//
// 设计原因：
// - Padding 初始值与 View 引用分离，避免 MainViewCache 过于臃肿
// - 缓存初始值防止 Edge-to-Edge 模式下 padding 无限叠加增长
// - 折叠屏设备上 padding 初始值会被缓存，防止叠加

package top.azek431.hzzs.ui.main

import android.view.View

/**
 * 主页 Padding 初始值缓存器。
 *
 * 接收 View 引用，提取并缓存它们的初始 padding 值。
 * 这些初始值用于在系统栏安全区域处理时，在初始 padding 基础上叠加 insets。
 */
class MainInsetCache {

    // ==================== Padding 初始值 ====================

    /** 顶部栏初始 padding 值（左/上/右/下） */
    var topBarPaddingStartInit = 0
        private set

    /** 顶部栏初始 padding 值（上） */
    var topBarPaddingTopInit = 0
        private set

    /** 顶部栏初始 padding 值（右） */
    var topBarPaddingEndInit = 0
        private set

    /** 顶部栏初始 padding 值（下） */
    var topBarPaddingBottomInit = 0
        private set

    /** 滚动区域初始 padding 值（左） */
    var scrollPaddingStartInit = 0
        private set

    /** 滚动区域初始 padding 值（上） */
    var scrollPaddingTopInit = 0
        private set

    /** 滚动区域初始 padding 值（右） */
    var scrollPaddingEndInit = 0
        private set

    /** 滚动区域初始 padding 值（下） */
    var scrollPaddingBottomInit = 0
        private set

    /**
     * 从 View 引用缓存中提取初始 padding 值。
     *
     * @param views 包含所有 View 引用缓存的结果对象
     */
    fun capture(views: MainViewCacheResult) {
        val topBar = views.topBarContainer
        val scroll = views.homeScrollView

        topBarPaddingStartInit = topBar.paddingStart
        topBarPaddingTopInit = topBar.paddingTop
        topBarPaddingEndInit = topBar.paddingEnd
        topBarPaddingBottomInit = topBar.paddingBottom

        scrollPaddingStartInit = scroll.paddingStart
        scrollPaddingTopInit = scroll.paddingTop
        scrollPaddingEndInit = scroll.paddingEnd
        scrollPaddingBottomInit = scroll.paddingBottom
    }
}
