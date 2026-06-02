package com.zeeshan.androidllmserver.http

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

/**
 * A tiny, dependency-free chat client served straight from the device at `/`.
 *
 * It talks to this same server's OpenAI-compatible endpoints and supports
 * attaching an image and/or an audio clip alongside text:
 *   - images go out as content `image_url` data-URLs
 *   - audio goes out as content `input_audio` (base64 + format)
 *
 * The page is served unauthenticated (it lives outside `/v1`, which is where
 * the bearer-auth plugin applies). The browser supplies the token on each API
 * call; it's entered once and kept in localStorage. Same trust boundary as the
 * LAN that can already reach the API.
 */
fun Routing.installWebUiRoutes() {
    get("/") { call.respondText(CHAT_HTML, ContentType.Text.Html) }
    get("/chat") { call.respondText(CHAT_HTML, ContentType.Text.Html) }
}

// Single self-contained document. JS uses string concatenation rather than
// template literals so the Kotlin raw-string doesn't fight over '$'.
private val CHAT_HTML = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>LLM Server — Chat</title>
<style>
  :root { color-scheme: dark; --bg:#0f1115; --panel:#171a21; --panel2:#1f242e;
          --line:#2a313d; --txt:#e7ebf0; --mut:#8b95a5; --accent:#5b9dff; --user:#243044; }
  * { box-sizing:border-box; }
  html,body { margin:0; height:100%; }
  body { background:var(--bg); color:var(--txt); font:15px/1.5 -apple-system,system-ui,Segoe UI,Roboto,sans-serif;
         display:flex; flex-direction:column; height:100dvh; }
  header { padding:10px 14px; background:var(--panel); border-bottom:1px solid var(--line);
           display:flex; align-items:center; gap:10px; }
  header h1 { font-size:15px; margin:0; font-weight:600; }
  header .status { font-size:12px; color:var(--mut); margin-left:auto; }
  header .dot { display:inline-block; width:8px; height:8px; border-radius:50%; background:#555; margin-right:5px; vertical-align:middle; }
  header .dot.ok { background:#3ecf8e; } header .dot.bad { background:#ff5d5d; }
  #cfg { background:var(--panel); border-bottom:1px solid var(--line); padding:10px 14px; display:none; gap:8px; flex-wrap:wrap; }
  #cfg.show { display:flex; }
  #cfg input { flex:1; min-width:180px; background:var(--panel2); border:1px solid var(--line); color:var(--txt);
               padding:8px 10px; border-radius:8px; font-size:13px; }
  #log { flex:1; overflow-y:auto; padding:16px; display:flex; flex-direction:column; gap:12px; }
  .msg { max-width:86%; padding:10px 13px; border-radius:14px; white-space:pre-wrap; word-wrap:break-word; }
  .msg.user { align-self:flex-end; background:var(--user); border-bottom-right-radius:4px; }
  .msg.bot  { align-self:flex-start; background:var(--panel2); border-bottom-left-radius:4px; }
  .msg.err  { align-self:center; background:#3a1f24; color:#ffb4b4; border:1px solid #5a2a31; font-size:13px; }
  .msg img  { max-width:200px; border-radius:8px; display:block; margin-bottom:6px; }
  .msg audio{ display:block; margin-bottom:6px; max-width:240px; }
  .chips { display:flex; gap:8px; padding:0 14px; flex-wrap:wrap; }
  .chip { background:var(--panel2); border:1px solid var(--line); border-radius:20px; padding:4px 10px; font-size:12px;
          color:var(--mut); display:flex; align-items:center; gap:6px; }
  .chip b { color:var(--txt); font-weight:500; }
  .chip span { cursor:pointer; color:var(--mut); }
  footer { padding:10px 12px; background:var(--panel); border-top:1px solid var(--line); }
  .row { display:flex; gap:8px; align-items:flex-end; }
  textarea { flex:1; resize:none; background:var(--panel2); border:1px solid var(--line); color:var(--txt);
             border-radius:12px; padding:10px 12px; font:inherit; max-height:140px; }
  button { background:var(--panel2); border:1px solid var(--line); color:var(--txt); border-radius:10px;
           padding:9px 12px; font-size:15px; cursor:pointer; }
  button:hover { border-color:var(--accent); }
  button.send { background:var(--accent); border-color:var(--accent); color:#06101f; font-weight:600; }
  button:disabled { opacity:.45; cursor:default; }
  button.rec { color:#ff6b6b; }
  .gear { margin-left:0; }
</style>
</head>
<body>
  <header>
    <h1>LLM Server</h1>
    <button class="gear" id="gear" title="Settings">&#9881;</button>
    <span class="status"><span class="dot" id="dot"></span><span id="stat">connecting…</span></span>
  </header>
  <div id="cfg">
    <input id="endpoint" placeholder="http://host:8085" autocomplete="off">
    <input id="token" placeholder="Bearer token" autocomplete="off">
  </div>
  <div id="log"></div>
  <div class="chips" id="chips"></div>
  <footer>
    <div class="row">
      <button id="imgBtn" title="Attach image">&#128247;</button>
      <button id="audBtn" title="Attach audio">&#127925;</button>
      <button id="recBtn" title="Record voice">&#127908;</button>
      <textarea id="input" rows="1" placeholder="Message… (Enter to send)"></textarea>
      <button class="send" id="send">Send</button>
    </div>
    <input type="file" id="imgFile" accept="image/*" hidden>
    <input type="file" id="audFile" accept="audio/*" hidden>
  </footer>
<script>
(function(){
  var ENDPOINT = localStorage.getItem('llm_endpoint') || (location.origin || 'http://localhost:8085');
  var TOKEN = localStorage.getItem('llm_token') || '';
  var endpointEl = document.getElementById('endpoint');
  var tokenEl = document.getElementById('token');
  endpointEl.value = ENDPOINT; tokenEl.value = TOKEN;

  var log = document.getElementById('log');
  var input = document.getElementById('input');
  var chips = document.getElementById('chips');
  var history = [];           // {role, content}  (content: string | parts[])
  var pendingImage = null;    // data URL
  var pendingAudio = null;    // {b64, format, mime}
  var busy = false;

  function authHeaders(json){
    var h = {}; if (json) h['Content-Type']='application/json';
    if (TOKEN) h['Authorization']='Bearer '+TOKEN; return h;
  }
  function save(){ localStorage.setItem('llm_endpoint', ENDPOINT); localStorage.setItem('llm_token', TOKEN); }

  document.getElementById('gear').onclick=function(){ document.getElementById('cfg').classList.toggle('show'); };
  endpointEl.onchange=function(){ ENDPOINT=endpointEl.value.replace(/\/+$/,''); save(); ping(); };
  tokenEl.onchange=function(){ TOKEN=tokenEl.value.trim(); save(); ping(); };

  function setStatus(ok, text){
    document.getElementById('dot').className='dot'+(ok===true?' ok':ok===false?' bad':'');
    document.getElementById('stat').textContent=text;
  }
  function ping(){
    setStatus(null,'connecting…');
    fetch(ENDPOINT+'/health').then(function(r){return r.json();}).then(function(j){
      if(j.model_loaded){ setStatus(true, j.model || 'model loaded'); }
      else { setStatus(false, j.error ? ('no model: '+j.error) : 'no model loaded'); }
    }).catch(function(){ setStatus(false,'unreachable'); });
  }

  function addMsg(role, node){
    var d=document.createElement('div'); d.className='msg '+role;
    if(typeof node==='string') d.textContent=node; else d.appendChild(node);
    log.appendChild(d); log.scrollTop=log.scrollHeight; return d;
  }
  function renderChips(){
    chips.innerHTML='';
    if(pendingImage){ chips.appendChild(makeChip('image', function(){ pendingImage=null; renderChips(); })); }
    if(pendingAudio){ chips.appendChild(makeChip('audio ('+pendingAudio.format+')', function(){ pendingAudio=null; renderChips(); })); }
  }
  function makeChip(label, onx){
    var c=document.createElement('div'); c.className='chip';
    var b=document.createElement('b'); b.textContent=label; c.appendChild(b);
    var x=document.createElement('span'); x.textContent='✕'; x.onclick=onx; c.appendChild(x); return c;
  }

  // ---- attachments ----
  var imgFile=document.getElementById('imgFile'), audFile=document.getElementById('audFile');
  document.getElementById('imgBtn').onclick=function(){ imgFile.click(); };
  document.getElementById('audBtn').onclick=function(){ audFile.click(); };
  imgFile.onchange=function(){ var f=imgFile.files[0]; if(!f)return;
    var r=new FileReader(); r.onload=function(){ pendingImage=r.result; renderChips(); }; r.readAsDataURL(f); imgFile.value=''; };
  audFile.onchange=function(){ var f=audFile.files[0]; if(!f)return; readAudio(f, f.type||'audio/wav'); audFile.value=''; };

  function readAudio(blob, mime){
    var r=new FileReader(); r.onload=function(){
      var s=r.result, c=s.indexOf(','); var b64=c>=0?s.slice(c+1):s;
      pendingAudio={ b64:b64, mime:mime, format:fmtFromMime(mime) }; renderChips();
    }; r.readAsDataURL(blob);
  }
  function fmtFromMime(m){ m=(m||'').toLowerCase();
    if(m.indexOf('wav')>=0)return 'wav'; if(m.indexOf('mp3')>=0||m.indexOf('mpeg')>=0)return 'mp3';
    if(m.indexOf('flac')>=0)return 'flac'; if(m.indexOf('ogg')>=0)return 'ogg';
    if(m.indexOf('webm')>=0)return 'webm'; return 'wav'; }

  // ---- voice recording ----
  var rec=null, recChunks=[], recMime='';
  document.getElementById('recBtn').onclick=function(){
    if(rec && rec.state==='recording'){ rec.stop(); return; }
    if(!navigator.mediaDevices||!window.MediaRecorder){ addMsg('err','Recording not supported in this browser.'); return; }
    navigator.mediaDevices.getUserMedia({audio:true}).then(function(stream){
      recChunks=[]; recMime = MediaRecorder.isTypeSupported('audio/webm')?'audio/webm':'';
      rec = recMime? new MediaRecorder(stream,{mimeType:recMime}) : new MediaRecorder(stream);
      rec.ondataavailable=function(e){ if(e.data.size>0) recChunks.push(e.data); };
      rec.onstop=function(){ stream.getTracks().forEach(function(t){t.stop();});
        var mime=rec.mimeType||recMime||'audio/webm'; readAudio(new Blob(recChunks,{type:mime}), mime);
        document.getElementById('recBtn').classList.remove('rec'); };
      rec.start(); document.getElementById('recBtn').classList.add('rec');
    }).catch(function(e){ addMsg('err','Mic error: '+e.message); });
  };

  // ---- send ----
  input.addEventListener('keydown', function(e){ if(e.key==='Enter' && !e.shiftKey){ e.preventDefault(); doSend(); } });
  input.addEventListener('input', function(){ input.style.height='auto'; input.style.height=Math.min(input.scrollHeight,140)+'px'; });
  document.getElementById('send').onclick=doSend;

  function doSend(){
    if(busy) return;
    var text=input.value.trim();
    if(!text && !pendingImage && !pendingAudio) return;
    if(!TOKEN){ document.getElementById('cfg').classList.add('show'); addMsg('err','Enter a bearer token in settings (gear icon).'); return; }

    // Bubble for the user (text + media previews)
    var u=document.createElement('div');
    if(pendingImage){ var im=document.createElement('img'); im.src=pendingImage; u.appendChild(im); }
    if(pendingAudio){ var au=document.createElement('audio'); au.controls=true; au.src='data:'+pendingAudio.mime+';base64,'+pendingAudio.b64; u.appendChild(au); }
    if(text){ var tn=document.createElement('div'); tn.textContent=text; u.appendChild(tn); }
    addMsg('user', u);

    // Build OpenAI content parts
    var parts=[];
    if(text) parts.push({type:'text', text:text});
    if(pendingImage) parts.push({type:'image_url', image_url:{url:pendingImage}});
    if(pendingAudio) parts.push({type:'input_audio', input_audio:{data:pendingAudio.b64, format:pendingAudio.format}});
    var content = (parts.length===1 && parts[0].type==='text') ? text : parts;
    history.push({role:'user', content:content});

    input.value=''; input.style.height='auto';
    pendingImage=null; pendingAudio=null; renderChips();
    stream();
  }

  function stream(){
    busy=true; var botEl=addMsg('bot',''); var acc='';
    fetch(ENDPOINT+'/v1/chat/completions', {
      method:'POST', headers:authHeaders(true),
      body: JSON.stringify({ model:'', stream:true, messages:history })
    }).then(function(res){
      if(!res.ok){ return res.text().then(function(t){ throw new Error('HTTP '+res.status+': '+t); }); }
      var reader=res.body.getReader(), dec=new TextDecoder(), buf='';
      function pump(){ return reader.read().then(function(r){
        if(r.done){ finish(); return; }
        buf+=dec.decode(r.value,{stream:true}); var i;
        while((i=buf.indexOf('\n'))>=0){
          var line=buf.slice(0,i).trim(); buf=buf.slice(i+1);
          if(line.indexOf('data:')!==0) continue;
          var data=line.slice(5).trim();
          if(data==='[DONE]'){ finish(); return; }
          try { var j=JSON.parse(data); var d=j.choices&&j.choices[0]&&j.choices[0].delta&&j.choices[0].delta.content;
            if(d){ acc+=d; botEl.textContent=acc; log.scrollTop=log.scrollHeight; } } catch(e){}
        }
        return pump();
      }); }
      function finish(){ if(acc) history.push({role:'assistant', content:acc}); else botEl.remove(); busy=false; }
      return pump();
    }).catch(function(e){ botEl.remove(); addMsg('err', e.message); busy=false; });
  }

  ping(); renderChips();
})();
</script>
</body>
</html>
""".trimIndent()
