package com.github.wooju.oracleinspector

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
private const val BUNDLE_FQN = "messages.OracleInspectorBundle"

/**
 * 플러그인의 UI 텍스트(라벨/툴팁/알림/대화창 등)는 모두 이 번들을 거친다.
 *  - 기본(default) properties: 영문 — Marketplace 페이지가 default를 사용
 *  - _ko: IDE 로케일이 ko_KR일 때 자동 선택, 키가 없으면 default(영문)로 자동 폴백
 */
object OracleInspectorBundle : DynamicBundle(BUNDLE_FQN) {

    @Nls
    @JvmStatic
    fun message(
        @PropertyKey(resourceBundle = BUNDLE_FQN) key: String,
        vararg params: Any,
    ): String = getMessage(key, *params)
}
