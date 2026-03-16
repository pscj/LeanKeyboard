package com.liskovsoft.leankeyboard.addons.remoteinput;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

public final class LocalTextBridgeServer {
    private static final String TAG = "LocalTextBridge";
    private static final int START_PORT = 18181;
    private static final int END_PORT = 18190;
    private static final int SOCKET_TIMEOUT_MS = 5000;
    private final TextSubmissionHandler mSubmissionHandler;
    private volatile boolean mRunning;
    private volatile String mAccessUrl;
    private volatile int mBoundPort = -1;
    private ServerSocket mServerSocket;
    private Thread mServerThread;

    public LocalTextBridgeServer(TextSubmissionHandler submissionHandler) {
        mSubmissionHandler = submissionHandler;
    }

    public synchronized void start(String hostAddress) throws IOException {
        stop();

        IOException lastError = null;

        for (int port = START_PORT; port <= END_PORT; port++) {
            try {
                ServerSocket serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(port));
                mServerSocket = serverSocket;
                mBoundPort = port;
                mAccessUrl = String.format(Locale.US, "http://%s:%d/", hostAddress, port);
                mRunning = true;
                mServerThread = new Thread(this::runServerLoop, "remote-input-http");
                mServerThread.start();
                return;
            } catch (IOException e) {
                lastError = e;
            }
        }

        throw lastError != null ? lastError : new IOException("No available port for local input bridge");
    }

    public synchronized void stop() {
        mRunning = false;
        mAccessUrl = null;
        mBoundPort = -1;

        if (mServerSocket != null) {
            try {
                mServerSocket.close();
            } catch (IOException e) {
                Log.w(TAG, "Error closing server socket", e);
            }
            mServerSocket = null;
        }

        if (mServerThread != null) {
            mServerThread.interrupt();
            mServerThread = null;
        }
    }

    public String getAccessUrl() {
        return mAccessUrl;
    }

    public int getBoundPort() {
        return mBoundPort;
    }

    private void runServerLoop() {
        while (mRunning && mServerSocket != null) {
            try {
                Socket client = mServerSocket.accept();
                handleClient(client);
            } catch (SocketException e) {
                if (mRunning) {
                    Log.w(TAG, "Socket error in HTTP bridge", e);
                }
                return;
            } catch (IOException e) {
                if (mRunning) {
                    Log.e(TAG, "Error accepting HTTP bridge connection", e);
                }
            }
        }
    }

    private void handleClient(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            BufferedInputStream inputStream = new BufferedInputStream(socket.getInputStream());
            OutputStream outputStream = socket.getOutputStream();

            HttpRequest request = readRequest(inputStream);

            if (request == null) {
                writeJsonResponse(outputStream, 400, false, "请求格式错误");
                return;
            }

            if ("GET".equals(request.method) && "/".equals(request.path)) {
                writeHtmlResponse(outputStream, buildInputPage());
                return;
            }

            if ("POST".equals(request.method) && "/submit".equals(request.path)) {
                handleSubmit(outputStream, request.body);
                return;
            }

            writeJsonResponse(outputStream, 404, false, "接口不存在");
        } catch (IOException e) {
            Log.e(TAG, "Error handling HTTP bridge client", e);
        }
    }

    private void handleSubmit(OutputStream outputStream, String requestBody) throws IOException {
        String text;

        try {
            text = extractTextFromPayload(requestBody);

            if (text == null) {
                writeJsonResponse(outputStream, 400, false, "缺少 text 字段");
                return;
            }
        } catch (JSONException e) {
            writeJsonResponse(outputStream, 400, false, "JSON 格式错误");
            return;
        }

        if (text.length() == 0) {
            writeJsonResponse(outputStream, 400, false, "文本不能为空");
            return;
        }

        SubmitResult result = mSubmissionHandler.submitText(text);
        writeJsonResponse(outputStream, result.statusCode, result.success, result.message);
    }

    static String extractTextFromPayload(String requestBody) throws JSONException {
        JSONObject payload = new JSONObject(requestBody);

        if (!payload.has("text") || payload.isNull("text")) {
            return null;
        }

        return payload.getString("text");
    }

    private HttpRequest readRequest(InputStream inputStream) throws IOException {
        byte[] headerBytes = readHeaderBytes(inputStream);

        if (headerBytes == null) {
            return null;
        }

        String headerText = new String(headerBytes, StandardCharsets.ISO_8859_1);
        String[] lines = headerText.split("\r\n");

        if (lines.length == 0) {
            return null;
        }

        String[] requestLine = lines[0].split(" ");

        if (requestLine.length < 2) {
            return null;
        }

        int contentLength = 0;

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int separator = line.indexOf(':');

            if (separator <= 0) {
                continue;
            }

            String headerName = line.substring(0, separator).trim();
            String headerValue = line.substring(separator + 1).trim();

            if ("Content-Length".equalsIgnoreCase(headerName)) {
                contentLength = Integer.parseInt(headerValue);
            }
        }

        byte[] bodyBytes = readBodyBytes(inputStream, contentLength);

        return new HttpRequest(
                requestLine[0].trim(),
                requestLine[1].trim(),
                new String(bodyBytes, StandardCharsets.UTF_8)
        );
    }

    private byte[] readHeaderBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int matched = 0;
        int current;

        while ((current = inputStream.read()) != -1) {
            buffer.write(current);

            if ((matched == 0 || matched == 2) && current == '\r') {
                matched++;
            } else if ((matched == 1 || matched == 3) && current == '\n') {
                matched++;
            } else {
                matched = current == '\r' ? 1 : 0;
            }

            if (matched == 4) {
                byte[] bytes = buffer.toByteArray();

                return Arrays.copyOf(bytes, bytes.length - 4);
            }
        }

        return null;
    }

    private byte[] readBodyBytes(InputStream inputStream, int contentLength) throws IOException {
        if (contentLength <= 0) {
            return new byte[0];
        }

        byte[] body = new byte[contentLength];
        int offset = 0;

        while (offset < contentLength) {
            int read = inputStream.read(body, offset, contentLength - offset);

            if (read == -1) {
                throw new IOException("Unexpected EOF while reading request body");
            }

            offset += read;
        }

        return body;
    }

    private void writeHtmlResponse(OutputStream outputStream, String html) throws IOException {
        writeResponse(outputStream, 200, "text/html; charset=UTF-8", html.getBytes(StandardCharsets.UTF_8));
    }

    private void writeJsonResponse(OutputStream outputStream, int statusCode, boolean success, String message) throws IOException {
        JSONObject payload = new JSONObject();

        try {
            payload.put("ok", success);
            payload.put("message", message);
        } catch (JSONException e) {
            throw new IOException("Unable to serialize JSON response", e);
        }

        writeResponse(outputStream, statusCode, "application/json; charset=UTF-8", payload.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void writeResponse(OutputStream outputStream, int statusCode, String contentType, byte[] body) throws IOException {
        String statusText = getStatusText(statusCode);
        String headers = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n" +
                "\r\n";

        outputStream.write(headers.getBytes(StandardCharsets.ISO_8859_1));
        outputStream.write(body);
        outputStream.flush();
    }

    private String buildInputPage() {
        return "<!doctype html><html><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<title>手机输入</title>" +
                "<style>" +
                "body{margin:0;padding:24px;font-family:sans-serif;background:#0f1720;color:#e8eef5;}" +
                "main{max-width:720px;margin:0 auto;}" +
                "h1{margin:0 0 12px;font-size:28px;}" +
                "textarea{width:100%;min-height:240px;padding:16px;border-radius:12px;border:1px solid #364759;background:#18222c;color:#f7fbff;font-size:16px;box-sizing:border-box;}" +
                "button{margin-top:16px;width:100%;padding:16px;border:0;border-radius:12px;background:#1a73e8;color:#f7fbff;font-size:18px;font-weight:700;}" +
                "button:disabled{opacity:.5;}" +
                "#status{margin-top:14px;min-height:24px;font-size:14px;}" +
                ".ok{color:#6fe3d5;}.error{color:#ff9696;}" +
                "</style></head><body><main>" +
                "<textarea id=\"text\" placeholder=\"支持多行、中文、符号、Emoji\"></textarea>" +
                "<button id=\"submit\" type=\"button\">发送到电视</button>" +
                "<div id=\"status\"></div></main>" +
                "<script>" +
                "const textarea=document.getElementById('text');" +
                "const button=document.getElementById('submit');" +
                "const status=document.getElementById('status');" +
                "function setStatus(message,ok){status.textContent=message||'';status.className=message?(ok?'ok':'error'):'';}" +
                "async function submitText(){const text=textarea.value;if(text.length===0){setStatus('文本不能为空',false);return;}button.disabled=true;setStatus('',true);" +
                "try{const response=await fetch('/submit',{method:'POST',headers:{'Content-Type':'application/json; charset=UTF-8'},body:JSON.stringify({text})});" +
                "const data=await response.json().catch(()=>({message:'响应解析失败'}));if(response.ok){textarea.value='';setStatus('',true);}else{setStatus(data.message||'发送失败',false);}}catch(error){setStatus('发送失败，请检查手机与电视是否在同一局域网',false);}finally{button.disabled=false;}}" +
                "button.addEventListener('click',submitText);" +
                "textarea.addEventListener('keydown',event=>{if((event.ctrlKey||event.metaKey)&&event.key==='Enter'){submitText();}});" +
                "</script></body></html>";
    }

    private String getStatusText(int statusCode) {
        switch (statusCode) {
            case 200:
                return "OK";
            case 400:
                return "Bad Request";
            case 404:
                return "Not Found";
            case 409:
                return "Conflict";
            default:
                return "Internal Server Error";
        }
    }

    public interface TextSubmissionHandler {
        SubmitResult submitText(String text);
    }

    public static final class SubmitResult {
        public final int statusCode;
        public final boolean success;
        public final String message;

        private SubmitResult(int statusCode, boolean success, String message) {
            this.statusCode = statusCode;
            this.success = success;
            this.message = message;
        }

        public static SubmitResult success(String message) {
            return new SubmitResult(200, true, message);
        }

        public static SubmitResult badRequest(String message) {
            return new SubmitResult(400, false, message);
        }

        public static SubmitResult unavailable(String message) {
            return new SubmitResult(409, false, message);
        }

        public static SubmitResult internalError(String message) {
            return new SubmitResult(500, false, message);
        }
    }

    private static final class HttpRequest {
        private final String method;
        private final String path;
        private final String body;

        private HttpRequest(String method, String path, String body) {
            this.method = method;
            this.path = path;
            this.body = body;
        }
    }
}
