package com.alexey.aircast

/**
 * v0.5.1-browser-visible: контракт раскладки (WebView).
 *
 * Диагноз (доказан скриншотом 2026-11-18 00:39 + кодом): WebView работал
 * (onPageFinished, JS-скан находит Kodik iframe, открывается страница
 * плеера), но рисовался в 0 px — экран добавлялся в корень без
 * параметров (WRAP_CONTENT по высоте), а контейнер WebView внутри него —
 * height=0 + weight=1. В LinearLayout с WRAP_CONTENT-высотой
 * weight-ребёнок получает 0 px.
 *
 * Фикс: экраны — MATCH_PARENT по высоте (у видимого экрана фиксированная
 * высота = остаток корня), WebView — MATCH_PARENT в контейнере.
 * Попутно чинится латентный баг остальных экранов: их корень —
 * ScrollView, который при WRAP_CONTENT вырастал на всю высоту контента
 * и срезал низ (скроллить было нечего, высота равнялась контенту);
 * при MATCH_PARENT ScrollView получает остаток окна и скроллит контент.
 *
 * v0.6.0: weight-контейнер и minHeight-страховка убраны вместе с
 * мастером — контейнер браузера теперь имеет явную высоту в пикселях:
 * 1 px в фоне (страница грузится, JS и перехват сети работают) и 420 dp
 * в фолбэке (браузер виден). Схлопывание в 0 по дизайну невозможно.
 *
 * Значения — те же константы, что в android.view.ViewGroup.LayoutParams
 * (MATCH_PARENT = -1, WRAP_CONTENT = -2). Файл намеренно без импортов
 * Android — контракт проверяется JVM-тестом в tests/TestMain.kt, а код
 * MainActivity.kt использует эти же константы (тест сверяет и исходник).
 */
object LayoutContract {
    const val MATCH_PARENT = -1
    const val WRAP_CONTENT = -2

    // Экраны в корне: фиксированная высота, иначе вес внутри не работает.
    const val SCREEN_WIDTH = MATCH_PARENT
    const val SCREEN_HEIGHT = MATCH_PARENT

    // Сам WebView в контейнере — на весь контейнер.
    const val WEBVIEW_WIDTH = MATCH_PARENT
    const val WEBVIEW_HEIGHT = MATCH_PARENT
}
