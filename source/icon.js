const { chromium } = require(require('child_process').execSync('npm root -g').toString().trim()+'/playwright');
// superellipse squircle (Apple-like continuous corners)
function squircle(n=5,s=1024){let d='';for(let i=0;i<=360;i++){const t=i/360*2*Math.PI,c=Math.cos(t),si=Math.sin(t);
 const x=Math.sign(c)*Math.pow(Math.abs(c),2/n)*s/2+s/2, y=Math.sign(si)*Math.pow(Math.abs(si),2/n)*s/2+s/2; d+=(i?'L':'M')+x.toFixed(1)+' '+y.toFixed(1);}return d+'Z';}
const INK='#2f2925';
function ticks(cx,cy,r1,r2,rl){let o='';for(let i=0;i<11;i++){const a=(-150+30*i)*Math.PI/180,big=i%5===0;
 const a1=r1, a2=big?r2+18:r2;
 o+=`<line x1="${cx+Math.sin(a)*a1}" y1="${cy-Math.cos(a)*a1}" x2="${cx+Math.sin(a)*a2}" y2="${cy-Math.cos(a)*a2}" stroke="${INK}" stroke-width="${big?16:11}" stroke-linecap="round"/>`;}
 return o;}
function knurl(cx,cy,r,teeth=48,depth=9){let d='';for(let i=0;i<teeth*2;i++){const a=i/(teeth*2)*Math.PI*2,rr=i%2?r:r-depth;d+=(i?'L':'M')+(cx+Math.sin(a)*rr).toFixed(1)+' '+(cy-Math.cos(a)*rr).toFixed(1);}return d+'Z';}
const defs=`<defs>
<linearGradient id="bg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#e6d3ae"/><stop offset=".55" stop-color="#cdb289"/><stop offset="1" stop-color="#a98a5f"/></linearGradient>
<radialGradient id="sheen" cx="50%" cy="0%" r="75%"><stop offset="0" stop-color="#fff" stop-opacity=".45"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient>
<filter id="grain"><feTurbulence type="fractalNoise" baseFrequency=".75" numOctaves="2" seed="7"/><feColorMatrix values="0 0 0 0 .25 0 0 0 0 .18 0 0 0 0 .1 0 0 0 .10 0"/><feComposite in2="SourceGraphic" operator="in"/></filter>
<radialGradient id="body" cx="42%" cy="30%" r="80%"><stop offset="0" stop-color="#5a5652"/><stop offset=".45" stop-color="#1f1d1b"/><stop offset="1" stop-color="#050505"/></radialGradient>
<radialGradient id="cap" cx="38%" cy="28%" r="85%"><stop offset="0" stop-color="#4c4945"/><stop offset=".6" stop-color="#151413"/><stop offset="1" stop-color="#000"/></radialGradient>
<linearGradient id="spec" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fff" stop-opacity=".55"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></linearGradient>
<filter id="shadow" x="-30%" y="-30%" width="160%" height="170%"><feDropShadow dx="0" dy="26" stdDeviation="24" flood-color="#2a1a08" flood-opacity=".55"/></filter>
<radialGradient id="led" cx="40%" cy="35%" r="70%"><stop offset="0" stop-color="#fff2d6"/><stop offset=".35" stop-color="#ff6a2a"/><stop offset="1" stop-color="#b3240a"/></radialGradient>
<filter id="glow" x="-100%" y="-100%" width="300%" height="300%"><feGaussianBlur stdDeviation="14"/></filter>
</defs>`;
function content(withText){
 const cx=512, cy=withText?462:512;
 let o=ticks(cx,cy,282,318);
 o+=`<g filter="url(#shadow)"><path d="${knurl(cx,cy,246)}" fill="url(#body)"/></g>`;
 o+=`<circle cx="${cx}" cy="${cy}" r="236" fill="none" stroke="#000" stroke-opacity=".5" stroke-width="3"/>`;
 o+=`<circle cx="${cx}" cy="${cy}" r="186" fill="url(#cap)" stroke="#000" stroke-width="4"/>`;
 o+=`<ellipse cx="${cx-30}" cy="${cy-110}" rx="120" ry="58" fill="url(#spec)"/>`;
 const a=40*Math.PI/180;
 o+=`<line x1="${cx+Math.sin(a)*40}" y1="${cy-Math.cos(a)*40}" x2="${cx+Math.sin(a)*176}" y2="${cy-Math.cos(a)*176}" stroke="#f6f2ea" stroke-width="22" stroke-linecap="round"/>`;
 // Strudel 37: magenta status LED (Inconsequent 37 orange, Inconsequent for Android green)
 o+=`<circle cx="790" cy="190" r="40" fill="#ff3fa4" opacity=".6" filter="url(#glow)"/><circle cx="790" cy="190" r="24" fill="#ff7cc4" stroke="#6a0c3e" stroke-width="3"/><circle cx="782" cy="182" r="8" fill="#fff0f8" opacity=".8"/>`;
 if(withText){
  o+=`<text x="512" y="880" text-anchor="middle" font-family="Barlow Condensed, Arial Narrow, sans-serif" font-weight="700" font-size="104" letter-spacing="6" fill="${INK}">STRUDEL <tspan fill="#a3155f">37</tspan></text>`;
  o+=`<text x="512" y="960" text-anchor="middle" font-family="Barlow Condensed, Arial Narrow, sans-serif" font-weight="700" font-size="58" letter-spacing="4" fill="#a3155f">note("&lt;C#4 [D3]&gt;")</text>`;
 }
 return o;
}
const bgLayer=`<rect width="1024" height="1024" fill="url(#bg)"/><rect width="1024" height="1024" fill="#c8b08c" filter="url(#grain)" opacity=".9"/><rect width="1024" height="1024" fill="url(#sheen)"/>`;
const SV=(inner)=>`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="1024" height="1024">${defs}${inner}</svg>`;
const sq=squircle();
const composite=SV(`<clipPath id="sq"><path d="${sq}"/></clipPath><g clip-path="url(#sq)">${bgLayer}${content(true)}</g><path d="${sq}" fill="none" stroke="#000" stroke-opacity=".18" stroke-width="3"/>`);
// adaptive layers: 108dp canvas, content scaled into the 66dp safe zone
const fg=SV(`<g transform="translate(512 512) scale(.6) translate(-512 -512)">${content(true)}</g>`);
const bg=SV(bgLayer);
(async()=>{
 const fs=require('fs');fs.writeFileSync('composite.svg',composite);
 const b=await chromium.launch(); const pg=await b.newPage();
 await pg.goto('https://fonts.googleapis.com/css2?family=Barlow+Condensed:wght@700').catch(()=>{});
 async function shot(svg,size,file){
  await pg.setViewportSize({width:size,height:size});
  await pg.setContent(`<html><head><link href="https://fonts.googleapis.com/css2?family=Barlow+Condensed:wght@700&display=block" rel="stylesheet"><style>html,body{margin:0;background:transparent}svg{width:${size}px;height:${size}px;display:block}</style></head><body>${svg}</body></html>`);
  await pg.waitForTimeout(800); await pg.evaluate(()=>document.fonts.ready);
  await pg.screenshot({path:file,omitBackground:true});
 }
 await shot(composite,512,'ic_launcher.png');
 await shot(composite,180,'apple_touch.png');
 await shot(fg,432,'ic_launcher_fg.png');
 await shot(bg,432,'ic_launcher_bg.png');
 await shot(composite,1024,'icon_1024.png');
 await b.close();
})();
