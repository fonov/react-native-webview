package com.reactnativecommunity.webview.web3;

import android.content.Context;
import androidx.annotation.Nullable;
import androidx.annotation.RawRes;
import android.text.TextUtils;
import android.util.Log;

import com.facebook.react.bridge.ReadableArray;
import com.reactnativecommunity.webview.R;
import com.reactnativecommunity.webview.WebViewCookieJar;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.StringBuilder;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class JsInjectorClient {

  private static final String DEFAULT_CHARSET = "utf-8";
  private static final String DEFAULT_MIME_TYPE = "text/html";
  private final static String JS_TAG_TEMPLATE = "<script type=\"text/javascript\">%s</script>";

  private final Context context;
  private final OkHttpClient httpClient;

  private ReadableArray injectJSConfig;
  private StringBuilder jsResource;


  public JsInjectorClient(Context context) {
    this.context = context;
    this.httpClient = createHttpClient();

    jsResource = new StringBuilder();
  }

  public void setInjectJSConfig(@Nullable ReadableArray injectJSConfig) {
    this.injectJSConfig = injectJSConfig;
  }

  @Nullable
  JsInjectorResponse loadUrl(final String url, final Map<String, String> headers) {
    Request request = buildRequest(url, headers);
    JsInjectorResponse result = null;
    try {
      Response response = httpClient.newCall(request).execute();
      result = buildResponse(response);
    } catch (Exception ex) {
      Log.d("REQUEST_ERROR", "", ex);
    }
    return result;
  }

  String assembleJs(Context context, String template) {
    if (injectJSConfig == null) {
      throw new UnsupportedOperationException("injectJSConfig must be set");
    }

    if (jsResource.toString().isEmpty()) {
      for (int i = 0; i < injectJSConfig.size(); i++) {
        ReadableArray config = injectJSConfig.getArray(i);
        if (config != null) {
          String type = config.getString(0);

          if (type != null && type.equals("file")) {
            String name = config.getString(1);
            if (name != null) {
              int resID = getResId(name, R.raw.class);
              String script = loadFile(context, resID);
              jsResource.append(script);
            }
          } else if (type != null && type.equals("string")) {
            String script = config.getString(1);
            if (script != null) {
              jsResource.append(script);
            }
          }
        }
      }
    }

    return String.format(template, jsResource.toString());
  }

  private static int getResId(String resName, Class<?> c) {
    try {
      Field idField = c.getDeclaredField(resName);
      return idField.getInt(idField);
    } catch (Exception e) {
      e.printStackTrace();
      return -1;
    }
  }

  @Nullable
  private JsInjectorResponse buildResponse(Response response) {
    String body = null;
    int code = response.code();
    try {
      if (response.isSuccessful()) {
        body = response.body().string();
      }
    } catch (IOException ex) {
      Log.d("READ_BODY_ERROR", "Ex", ex);
    }
    Request request = response.request();
    Response prior = response.priorResponse();
    boolean isRedirect = prior != null && prior.isRedirect();
    String result = injectJS(body);
    String contentType = getContentTypeHeader(response);
    String charset = getCharset(contentType);
    String mime = getMimeType(contentType);
    String finalUrl = request.url().toString();
    return new JsInjectorResponse(result, code, finalUrl, mime, charset, isRedirect);
  }

  String injectJS(String html) {
    String js = assembleJs(context, JS_TAG_TEMPLATE);
    return injectJS(html, js);
  }

  String injectJS(String html, String js) {
    if (TextUtils.isEmpty(html)) {
      return html;
    }
    int position = getInjectionPosition(html);
    if (position >= 0) {
      String beforeTag = html.substring(0, position);
      String afterTab = html.substring(position);
      return beforeTag + js + afterTab;
    }
    return html;
  }

  private int getInjectionPosition(String body) {
    body = body.toLowerCase();
    int ieDetectTagIndex = body.indexOf("<!--[if");
    int scriptTagIndex = body.indexOf("<script");

    int index;
    if (ieDetectTagIndex < 0) {
      index = scriptTagIndex;
    } else {
      index = Math.min(scriptTagIndex, ieDetectTagIndex);
    }
    if (index < 0) {
      index = body.indexOf("</head");
    }
    return index;
  }

  @Nullable
  private Request buildRequest(String url, Map<String, String> headers) {
    HttpUrl httpUrl = HttpUrl.parse(url);
    if (httpUrl == null) {
      return null;
    }
    Request.Builder requestBuilder = new Request.Builder()
      .get()
      .url(httpUrl);
    Set<String> keys = headers.keySet();
    for (String key : keys) {
      requestBuilder.addHeader(key, headers.get(key));
    }
    return requestBuilder.build();
  }

  private static String loadFile(Context context, @RawRes int rawRes) {
    byte[] buffer = new byte[0];
    try {
      InputStream in = context.getResources().openRawResource(rawRes);
      buffer = new byte[in.available()];
      int len = in.read(buffer);
      if (len < 1) {
        throw new IOException("Nothing is read.");
      }
    } catch (Exception ex) {
      Log.d("READ_JS_TAG", "Ex", ex);
    }
    return new String(buffer);
  }

  private String getMimeType(String contentType) {
    Matcher regexResult = Pattern.compile("^.*(?=;)").matcher(contentType);
    if (regexResult.find()) {
      return regexResult.group();
    }
    return DEFAULT_MIME_TYPE;
  }

  private String getCharset(String contentType) {
    Matcher regexResult = Pattern.compile("charset=([a-zA-Z0-9-]+)").matcher(contentType);
    if (regexResult.find()) {
      if (regexResult.groupCount() >= 2) {
        return regexResult.group(1);
      }
    }
    return DEFAULT_CHARSET;
  }

  @Nullable
  private String getContentTypeHeader(Response response) {
    Headers headers = response.headers();
    String contentType;
    if (TextUtils.isEmpty(headers.get("Content-Type"))) {
      if (TextUtils.isEmpty(headers.get("content-Type"))) {
        contentType = "text/data; charset=utf-8";
      } else {
        contentType = headers.get("content-Type");
      }
    } else {
      contentType = headers.get("Content-Type");
    }
    if (contentType != null) {
      contentType = contentType.trim();
    }
    return contentType;
  }

  private OkHttpClient createHttpClient() {
    return new OkHttpClient.Builder()
      .cookieJar(new WebViewCookieJar())
      .build();
  }
}