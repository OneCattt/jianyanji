// 仅对隔离本地测试库运行；凭据通过环境变量传入，不记录会话令牌。
import fs from 'node:fs/promises';
const base = 'http://127.0.0.1:3117';
const results=[];
async function call(path,options={}) { return fetch(base+path,{...options,headers:{Origin:base,'Content-Type':'application/json',...options.headers}}); }
async function check(name,path,status,options={},verify) { const r=await call(path,options); const body=await r.json(); const pass=r.status===status&&(!verify||verify(body)); results.push({name,status:r.status,pass});if(!pass)throw Error(name+' failed '+r.status+' '+JSON.stringify(body));return body; }
async function login(username) { const r=await call('/api/admin/login',{method:'POST',body:JSON.stringify({username,password:process.env.LOCAL_REVIEW_PASSWORD})});if(r.status!==200)throw Error('本地后台登录失败 '+r.status); return {Cookie:r.headers.get('set-cookie').split(';')[0]}; }
await check('匿名不能读取本人寄存','/api/mini/storage',401);
await check('匿名不能读取后台寄存','/api/admin/storage',401);
const headers=await login('storage-local-review');
await check('后台分页台账','/api/admin/storage?pageSize=20',200,{headers},b=>b.items.length===20&&b.total>200);
await check('后台提货记录','/api/admin/pickups?pageSize=20',200,{headers},b=>Array.isArray(b.items));
await check('直接现金核款入口关闭','/api/admin/orders/1/receipt',409,{method:'POST',headers,body:'{}'},b=>b.code==='POINTS_ONLY');
const exported=await call('/api/admin/exports/storage/lots',{headers});const bytes=new Uint8Array(await exported.arrayBuffer());results.push({name:'HTTP导出返回真实xlsx',status:exported.status,pass:exported.status===200&&bytes[0]===80&&bytes[1]===75});
const client=await check('普通顾客登录','/api/auth/dev',200,{method:'POST',body:JSON.stringify({account:'storage-http-smoke'})});const clientHeaders={Authorization:'Bearer '+client.token};
await check('积分支付能力','/api/payments/capabilities',200,{headers:clientHeaders},b=>b.pointsAvailable===true&&b.wechatAvailable===false&&b.offlineAvailable===false);
await check('普通顾客可查积分钱包','/api/wallet',200,{headers:clientHeaders});
await check('本人接口忽略伪造用户编号','/api/mini/storage?userId=1&limit=20',200,{headers:clientHeaders},b=>b.total===0&&b.hasMore===false);
await check('顾客不能读取后台','/api/admin/storage',401,{headers:clientHeaders});
await check('顾客不能读取他人提货','/api/pickups/1',404,{headers:clientHeaders});
const wh=await login('storage-local-warehouse');
await check('仓库可查寄存','/api/admin/storage',200,{headers:wh});
await check('仓库不可退积分运费','/api/admin/pickups/1/refund-fee',403,{method:'POST',headers:wh,body:JSON.stringify({reason:'验证权限'})});
await check('仓库不可修改身份','/api/admin/users/1/identity',403,{method:'POST',headers:wh,body:'{}'});
const report={time:new Date().toISOString(),environment:'isolated local MySQL, Java 8 packaged JAR',results};await fs.writeFile(new URL('./http-smoke.json',import.meta.url),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report,null,2));if(results.some(r=>!r.pass))process.exitCode=1;
