package dev.halftoast.stmobile.chat

import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.halftoast.stmobile.StMobileApp
import dev.halftoast.stmobile.ui.StTheme
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads SillyTavern's theme CSS variables from the page and watches for changes. The page posts
 * a JSON object of [StTheme.CSS_VARS] through an origin-restricted web message listener.
 */
object ThemeBridge {
    const val NAME = "STMobileTheme"
    private const val MAX_MESSAGE_CHARS = 4096

    /** A JSON object of string values; unknown keys are dropped. Null for anything else or oversized input. */
    fun parseMessage(json: String): Map<String, String>? {
        if (json.length > MAX_MESSAGE_CHARS) return null
        val obj = try {
            JSONObject(json)
        } catch (_: Exception) {
            return null
        }
        val out = LinkedHashMap<String, String>()
        for (k in StTheme.CSS_VARS) {
            val v = obj.opt(k)
            if (v is String) out[k] = v
        }
        return out
    }

    /**
     * Evaluates to the theme JSON string (null on the login page). Also installs, once per page, a
     * MutationObserver on the root element that re-posts the theme, at most once per 300 ms.
     */
    fun readAndWatchScript(): String {
        val vars = JSONArray(StTheme.CSS_VARS).toString()
        return """(function(){
if(location.pathname.indexOf('/login')===0)return null;
var V=$vars;
function read(){var cs=getComputedStyle(document.documentElement),o={};for(var i=0;i<V.length;i++)o[V[i]]=cs.getPropertyValue(V[i]).trim();return JSON.stringify(o);}
if(!window.__stmThemeWatch){
window.__stmThemeWatch=true;
var last=0,timer=null;
function send(){timer=null;last=Date.now();try{$NAME.postMessage(read());}catch(e){}}
new MutationObserver(function(){
if(timer!==null)return;
var wait=300-(Date.now()-last);
if(wait<=0)send();else timer=setTimeout(send,wait);
}).observe(document.documentElement,{attributes:true,attributeFilter:['style','class']});
}
return read();
})()"""
    }

    fun install(webView: WebView, origin: String?, onTheme: (Map<String, String>) -> Unit) {
        if (origin == null) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Log.w(StMobileApp.LOG_TAG, "WEB_MESSAGE_LISTENER unsupported; theme will not follow ST")
            return
        }
        WebViewCompat.addWebMessageListener(webView, NAME, setOf(origin)) { _, message, _, isMainFrame, _ ->
            if (!isMainFrame) return@addWebMessageListener
            message.data?.let(::parseMessage)?.let(onTheme)
        }
    }
}
