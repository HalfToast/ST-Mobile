package dev.halftoast.stmobile.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetCachePolicyTest {
    @Test fun cachesCoreScripts() = assertTrue(AssetCachePolicy.shouldCache("GET", "/scripts/openai.js"))
    @Test fun cachesLibCssFontsImgLocales() = listOf("/lib/jquery.js", "/css/st-tailwind.css", "/webfonts/fa-solid-900.woff2", "/img/logo.png", "/locales/en.json", "/lib.js", "/script.js", "/style.css").forEach { assertTrue(it, AssetCachePolicy.shouldCache("GET", it)) }
    @Test fun neverCachesThirdPartyExtensions() = assertFalse(AssetCachePolicy.shouldCache("GET", "/scripts/extensions/third-party/prepreset/index.js"))
    @Test fun neverCachesUserCss() = assertFalse(AssetCachePolicy.shouldCache("GET", "/css/user.css"))
    @Test fun neverCachesApiOrUserData() = listOf("/api/characters/all", "/characters/Seraphina.png", "/backgrounds/x.jpg", "/thumbnail?type=avatar&file=a.png", "/", "/index.html", "/version").forEach { assertFalse(it, AssetCachePolicy.shouldCache("GET", it)) }
    @Test fun neverCachesNonGet() = assertFalse(AssetCachePolicy.shouldCache("POST", "/scripts/openai.js"))
}
