/* Video stays on the authenticated HTTPS relay; no peer connections or IP exchange. */
'use strict';
window.VchVideo=(()=>{
    const local=new Map(), remote=new Map();
    let api,account,active=false,timer,busy=false,cursor=-1,epoch=0;
    const $=id=>document.getElementById(id);
    const message=text=>{$('video-status').textContent=text};
    const to64=bytes=>{let s='';for(const b of bytes)s+=String.fromCharCode(b);return btoa(s)};
    const from64=s=>Uint8Array.from(atob(s),c=>c.charCodeAt(0));
    // Four-byte big-endian header length, UTF-8 JSON header, then an Annex B AVC or VP8 keyframe.
    function packet(header,bytes){const h=new TextEncoder().encode(JSON.stringify(header)),out=new Uint8Array(4+h.length+bytes.length);new DataView(out.buffer).setUint32(0,h.length);out.set(h,4);out.set(bytes,4+h.length);return out}
    function unpack(bytes){if(bytes.length<5)throw Error('Invalid video packet');const n=new DataView(bytes.buffer,bytes.byteOffset).getUint32(0);if(n>1024||n+4>=bytes.length)throw Error('Invalid video header');return {header:JSON.parse(new TextDecoder().decode(bytes.subarray(4,4+n))),data:bytes.subarray(4+n)}}
    async function configuration(width,height,kbps){
        if(!window.VideoEncoder||!window.VideoFrame)throw Error('Video encoding needs a browser with WebCodecs support. Try current Edge or Chrome.');
        for(const hardwareAcceleration of ['prefer-hardware','no-preference'])for(const codec of ['avc1.42001f','vp8']){
            const config={codec,width,height,bitrate:Math.floor(kbps*750),framerate:8,latencyMode:'realtime',bitrateMode:'variable',hardwareAcceleration};
            if(codec.startsWith('avc1'))config.avc={format:'annexb'};
            try{if((await VideoEncoder.isConfigSupported(config)).supported)return config}catch(e){}
        }
        throw Error('No supported video encoder on this device.');
    }
    async function stop(source){
        const s=local.get(source);if(!s)return;local.delete(source);s.stopped=true;clearInterval(s.timer);
        if(s.capture)s.capture.getTracks().forEach(t=>t.stop());
        if(s.video){s.video.srcObject=null;s.video.remove()}
        if(s.encoder&&s.encoder.state!=='closed')s.encoder.close();
        s.button.textContent=source==='screen'?'Share screen':'Start webcam';s.button.disabled=!active;
        if(s.native){try{window.VchNative.stopVideo(source)}catch(e){}}
        if(s.id)try{await api('/video/stop',{stream:s.id})}catch(e){}
    }
    async function start(source,button,input){
        if(local.has(source)){await stop(source);return}
        const s={button,stopped:false,sending:false,charges:[],capture:null,id:null};local.set(source,s);button.disabled=true;
        try{
            const kbps=Number(input.value);if(!Number.isInteger(kbps)||kbps<50||kbps>1000)throw Error('Choose 50–1000 kbps.');
            s.kbps=kbps;
            if(window.VchNative?.startVideo){
                s.native=true;
                const result=JSON.parse(window.VchNative.startVideo(source,kbps));if(result.error)throw Error(result.error);
                button.textContent='Stop '+(source==='screen'?'screen share':'webcam');button.disabled=false;
                message('Approve the device capture prompt to start sharing.');return;
            }
            // Invoke screen selection directly from the click, before awaiting codec negotiation.
            s.capture=source==='screen'?await navigator.mediaDevices.getDisplayMedia({video:{frameRate:8},audio:false}):await navigator.mediaDevices.getUserMedia({video:{width:{ideal:640},height:{ideal:360},frameRate:{ideal:8,max:8}},audio:false});
            if(s.stopped||!active){s.capture.getTracks().forEach(t=>t.stop());return}
            const width=source==='screen'?960:640,height=source==='screen'?540:360;
            s.config=await configuration(width,height,kbps);
            s.id=(await api('/video/start',{source,kbps})).stream;
            if(s.stopped||!active){await api('/video/stop',{stream:s.id});s.capture.getTracks().forEach(t=>t.stop());return}
            s.kbps=kbps;s.canvas=document.createElement('canvas');s.canvas.width=width;s.canvas.height=height;
            const draw=s.canvas.getContext('2d',{alpha:false});s.video=document.createElement('video');s.video.muted=true;s.video.playsInline=true;s.video.srcObject=s.capture;await s.video.play();
            if(s.stopped||!active)return;
            s.encoder=new VideoEncoder({error:e=>{message(e.message);stop(source)},output:async chunk=>{
                if(s.stopped||s.sending||chunk.type!=='key')return;
                const bytes=new Uint8Array(chunk.byteLength);chunk.copyTo(bytes);
                const data=packet({codec:s.config.codec,width,height,timestamp:chunk.timestamp},bytes),now=performance.now();
                s.charges=s.charges.filter(c=>now-c.time<1100);
                // Conservative payload budget: encryption/compression overhead plus a 100ms timing margin.
                const cost=data.length+256;
                if(cost+s.charges.reduce((n,c)=>n+c.bytes,0)>s.kbps*125)return;
                s.charges.push({time:now,bytes:cost});s.sending=true;
                try{const envelope=await api('/video/encode',{stream:s.id,data:to64(data)});if(!s.stopped&&active)await api('/video/media',{stream:s.id,envelope})}
                catch(e){if(!s.stopped)message(e.message)}finally{s.sending=false}
            }});s.encoder.configure(s.config);
            // Independent keyframes let listeners opt in immediately and tolerate dropped or delayed frames.
            s.timer=setInterval(()=>{
                if(s.stopped||s.sending||s.encoder.encodeQueueSize>0||s.video.readyState<2)return;
                draw.fillStyle='#161616';draw.fillRect(0,0,width,height);
                const scale=Math.min(width/s.video.videoWidth,height/s.video.videoHeight),w=s.video.videoWidth*scale,h=s.video.videoHeight*scale;
                draw.drawImage(s.video,(width-w)/2,(height-h)/2,w,h);
                const frame=new VideoFrame(s.canvas,{timestamp:Math.round(performance.now()*1000)});
                try{s.encoder.encode(frame,{keyFrame:true})}catch(e){message(e.message);stop(source)}finally{frame.close()}
            },125);
            s.capture.getVideoTracks()[0].onended=()=>stop(source);
            button.textContent='Stop '+(source==='screen'?'screen share':'webcam');button.disabled=false;
            message(s.config.hardwareAcceleration==='prefer-hardware'?'Sharing · hardware encoding requested.':'Sharing · device uses its available encoder.');
        }catch(e){await stop(source);message(e.message)}
    }
    function controls(){
        $('video-controls').replaceChildren();
        for(const source of ['screen','camera']){
            const row=document.createElement('div'),label=document.createElement('label'),input=document.createElement('input'),button=document.createElement('button');
            input.type='number';input.min='50';input.max='1000';input.step='50';input.value='500';input.setAttribute('aria-label',(source==='screen'?'Screen':'Webcam')+' bitrate in kbps');
            label.append(source==='screen'?'Screen bitrate (kbps, max 1000) ':'Webcam bitrate (kbps, max 1000) ',input);row.append(label,button);$('video-controls').append(row);
            button.textContent=source==='screen'?'Share screen':'Start webcam';button.onclick=()=>start(source,button,input);
            if(source==='screen'&&!navigator.mediaDevices?.getDisplayMedia&&!window.VchNative?.startVideo){button.disabled=true;button.title='Screen capture is unavailable on this device'}
            input.onchange=async()=>{const s=local.get(source),kbps=Number(input.value);if(!Number.isInteger(kbps)||kbps<50||kbps>1000){input.value=String(s?.kbps||500);message('Choose 50–1000 kbps.');return}if(!s)return;
                input.disabled=true;try{if(s.native){const out=JSON.parse(window.VchNative.configureVideo(source,kbps));if(out.error)throw Error(out.error);s.kbps=kbps}else if(s.id){await api('/video/configure',{stream:s.id,kbps});s.kbps=kbps;s.config.bitrate=kbps*750;s.encoder.configure(s.config)}}catch(e){message(e.message);await stop(source)}finally{input.disabled=false}};
        }
        const hint=document.createElement('small');hint.textContent='Each stream: 50–1000 kbps. Hardware encoding is preferred when available. Select Receive for each incoming stream.';$('video-controls').append(hint);
    }
    function removeRemote(id){const r=remote.get(id);if(!r)return;r.wanted=false;if(r.decoder&&r.decoder.state!=='closed')r.decoder.close();r.row.remove();remote.delete(id)}
    function listings(streams){
        const ids=new Set(streams.filter(s=>s.author!==account).map(s=>s.id));for(const id of remote.keys())if(!ids.has(id))removeRemote(id);
        for(const s of streams){if(s.author===account)continue;let r=remote.get(s.id);
            if(!r){const row=document.createElement('article'),label=document.createElement('label'),check=document.createElement('input'),text=document.createTextNode(''),canvas=document.createElement('canvas');
                check.type='checkbox';canvas.hidden=true;canvas.style.maxWidth='100%';canvas.style.height='auto';label.append(check,text);row.append(label,canvas);$('video-streams').append(row);
                r={row,text,canvas,check,wanted:false,decoder:null,author:s.author};remote.set(s.id,r);
                check.onchange=()=>{r.wanted=check.checked;canvas.hidden=!r.wanted;if(!r.wanted){if(r.decoder&&r.decoder.state!=='closed')r.decoder.close();r.decoder=null;canvas.getContext('2d').clearRect(0,0,canvas.width,canvas.height)}};
            }
            r.text.textContent=' Receive '+(s.source==='screen'?'screen':'webcam')+' from '+s.author.slice(0,12)+' · '+s.kbps+' kbps';
        }
    }
    async function render(item,ticket){
        const r=remote.get(item.stream);if(!r?.wanted||!active||ticket!==epoch)return;
        try{
            const out=await api('/video/decode',{stream:item.stream,author:r.author,envelope:item.envelope});
            if(!r.wanted||!active||ticket!==epoch||remote.get(item.stream)!==r)return;
            const {header:h,data}=unpack(from64(out.data));
            if(!['avc1.42001f','vp8'].includes(h.codec)||!Number.isInteger(h.width)||!Number.isInteger(h.height)||h.width<2||h.height<2||h.width>1920||h.height>1080||!Number.isFinite(h.timestamp))throw Error('Invalid video configuration');
            if(!window.VideoDecoder)throw Error('Receiving video needs a WebCodecs-capable browser.');
            if(!r.decoder||r.decoder.state==='closed'){
                r.decoder=new VideoDecoder({output:frame=>{try{if(r.wanted&&active){r.canvas.width=frame.displayWidth;r.canvas.height=frame.displayHeight;r.canvas.getContext('2d').drawImage(frame,0,0)}}finally{frame.close()}},error:e=>{message(e.message);r.decoder=null}});
                r.decoder.configure({codec:h.codec,codedWidth:h.width,codedHeight:h.height,hardwareAcceleration:'prefer-hardware'});
            }
            if(r.decoder.decodeQueueSize<2)r.decoder.decode(new EncodedVideoChunk({type:'key',timestamp:h.timestamp,data}));
        }catch(e){if(active&&r.wanted)message(e.message)}
    }
    async function poll(){if(!active||busy)return;busy=true;const ticket=epoch;try{
        const result=await api('/video/poll',{after:cursor,receive:[...remote].filter(([,r])=>r.wanted).map(([id])=>id)});
        if(!active||ticket!==epoch)return;cursor=result.cursor;listings(result.streams);
        for(const item of result.frames)await render(item,ticket);
    }catch(e){if(active&&ticket===epoch){message(e.message);await disconnect()}}finally{busy=false}}
    async function connect(request,who){if(active)return;api=request;account=who;active=true;epoch++;cursor=-1;controls();message('Connected · incoming video is off until you select Receive.');timer=setInterval(poll,250);await poll()}
    async function disconnect(){active=false;epoch++;clearInterval(timer);await Promise.all([...local.keys()].map(stop));for(const id of remote.keys())removeRemote(id);for(const b of $('video-controls').querySelectorAll('button'))b.disabled=true}
    return {connect,disconnect,nativeStopped(source,error){stop(source);if(error)message(error)}};
})();
