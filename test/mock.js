// 測試用假資料：模擬 Yahoo、證交所 MIS、幣安、恐懼貪婪
(()=>{
const rnd=s=>{let x=Math.sin(s)*10000;return x-Math.floor(x)};
const base={'%5ETWII':22345,'%5EN225':39512,'TWD%3DX':31.85,'JPYTWD%3DX':0.2108,'GC%3DF':2712,'%5EVIX':14.8,'KRWTWD%3DX':0.0226,'4938.TW':95,'2330.TW':1460};
function chart(sym,u){let b=base[sym]||100+(sym.length*37)%900;const n=u.includes('range=1d')?70:60;const ts=[],c=[],o=[],h=[],l=[],v=[];let p=b;const t0=Math.floor(Date.now()/1000)-n*300;
 for(let i=0;i<n;i++){p=p*(1+(rnd(i+sym.length)-.48)*.004);ts.push(t0+i*300);o.push(p*.999);c.push(p);h.push(p*1.002);l.push(p*.997);v.push(1000+i*10)}
 const pv=b*(1+(rnd(sym.length)-.5)*.03);return{chart:{result:[{meta:{regularMarketPrice:p,previousClose:pv,regularMarketDayHigh:p*1.01,regularMarketDayLow:p*.99,regularMarketVolume:3812e8,regularMarketTime:Date.now()/1000,fiftyTwoWeekHigh:p*1.2,fiftyTwoWeekLow:p*.8,shortName:sym,currentTradingPeriod:{regular:{start:Date.now()/1000-3600,end:Date.now()/1000+3600}}},timestamp:ts,indicators:{quote:[{open:o,close:c,high:h,low:l,volume:v}]}}]}}}
const TSE={'2330':['台積電',1460],'2317':['鴻海',200],'4938':['和碩',95],'0050':['元大台灣50',200],'0056':['元大高股息',38],'t00':['發行量加權股價指數',22500]};
const OTC={'6488':['環球晶',420],'00679b':['元大美債20年',27.5],'00687b':['國泰20年美債',30]};
function mis(keys){const out=[];for(const k of keys){const [ex,rest]=k.split('_');const c=rest.replace('.tw','');const d=(ex==='tse'?TSE:OTC)[c];if(!d){if(keys.length>1)return{msgArray:[],rtcode:'0000'};continue}
  const b0=d[1],z=+(b0*(1+(Math.random()-.4)*.03)).toFixed(2);out.push({ex,c:c.toUpperCase(),n:d[0],z:String(z),y:String(b0),o:String(b0*1.005),h:String(b0*1.02),l:String(b0*.99),v:'28431',tv:'12',u:String(b0*1.1),w:String(b0*.9),
  b:[z,z-.5,z-1,z-1.5,z-2].join('_')+'_',g:'312_655_428_1102_387_',a:[z+.5,z+1,z+1.5,z+2,z+2.5].join('_')+'_',f:'205_540_1876_233_419_',t:'09:41:25',tlong:String(Date.now())})}return{msgArray:out,rtcode:'0000'}}
window.fetch=async u=>{let body;
 if(u.includes('finance/chart/')){const sym=u.split('chart/')[1].split('?')[0];if(/^9999|^ZZZZ/.test(decodeURIComponent(sym)))return{ok:false,status:404,json:async()=>({})};body=chart(sym,u)}
 else if(u.includes('getStockInfo'))body=mis(decodeURIComponent(u.split('ex_ch=')[1].split('&')[0]).split('|'));
 else if(u.includes('ticker/24hr')){const syms=JSON.parse(decodeURIComponent(u.split('symbols=')[1]));body=syms.map((s,i)=>({symbol:s,lastPrice:String(s==='BTCUSDT'?98420:1+i*3.3),openPrice:String(s==='BTCUSDT'?96400:1+i*3.2),highPrice:'1',lowPrice:'1',quoteVolume:'123456789',volume:'1000'}))}
 else if(u.includes('ticker/price'))body={symbol:'BTCUSDT',price:'98420.00'};
 else if(u.includes('klines')){body=[];let p=98000;for(let i=0;i<96;i++){p*=1+(rnd(i)-.48)*.004;body.push([Date.now()-(96-i)*9e5,String(p*.999),String(p*1.002),String(p*.997),String(p),'10'])}}
 else if(u.includes('fng'))body={data:[{value:'72'}]};
 else return{ok:false,status:404,json:async()=>({})};
 return{ok:true,json:async()=>body}};
window.WebSocket=class{constructor(){setTimeout(()=>{this.onopen&&this.onopen();setInterval(()=>this.onmessage&&this.onmessage({data:JSON.stringify({data:{s:'BTCUSDT',c:String(98420+Math.random()*100),o:'96400',h:'99000',l:'96000',q:'1e9',v:'1'}})}),500)},50)}close(){}};
window.confirm=()=>{throw new Error('不應使用 confirm()')};window.alert=()=>{throw new Error('不應使用 alert()')};
})();
