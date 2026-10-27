// 仅在本地隔离测试库演练积分购货、寄存和实际HTTP履约，不代表真实收款或发货。
import fs from 'node:fs/promises';
const base='http://127.0.0.1:3117', results=[];
async function request(name,path,method='GET',body,headers={}) {
 const response=await fetch(base+path,{method,headers:{Origin:base,'Content-Type':'application/json',...headers},...(body?{body:JSON.stringify(body)}:{})});const value=await response.json();results.push({name,status:response.status,pass:response.ok});if(!response.ok)throw Error(name+': '+JSON.stringify(value));return value;
}
const login=await fetch(base+'/api/admin/login',{method:'POST',headers:{Origin:base,'Content-Type':'application/json'},body:JSON.stringify({username:'storage-local-review',password:process.env.LOCAL_REVIEW_PASSWORD})});if(!login.ok)throw Error('测试后台登录失败');const admin={Cookie:login.headers.get('set-cookie').split(';')[0]};
const stamp=Date.now().toString();
const client=await request('顾客HTTP登录','/api/auth/dev','POST',{account:'storage-chain-'+stamp});const user={Authorization:'Bearer '+client.token};
const address=await request('保存测试地址','/api/addresses','POST',{recipient:'本地测试',phone:'13800000000',province:'浙江省',city:'杭州市',district:'西湖区',detail:'隔离测试地址',isDefault:true},user);
await request('总部登记测试积分','/api/admin/receipts','POST',{userId:client.user.id,purpose:'points',sourceChannel:'wechat_company',accountRef:'隔离测试总部微信',externalTransactionId:'http-receipt-'+stamp,amountCents:50000,receivedAt:new Date().toISOString(),evidence:'本地隔离测试记录，不代表真实核款',reason:'积分寄存HTTP演练',verified:true},admin);
const quote=await request('按行全寄存报价','/api/checkout/quote','POST',{addressId:address.id,items:[{skuId:Number(process.env.LOCAL_REVIEW_SKU),quantity:3,shipQuantity:0}],storedItems:[]},user);
const order=await request('创建待支付订单','/api/orders','POST',{quoteId:quote.id,requestKey:'http-order-'+stamp},user);
await request('积分支付并入寄存','/api/orders/'+order.id+'/pay/points','POST',undefined,user);
await request('重复支付幂等','/api/orders/'+order.id+'/pay/points','POST',undefined,user);
const lots=await request('查询本人寄存','/api/mini/storage?limit=20','GET',undefined,user);if(lots.items.length!==1||lots.items[0].remaining_qty!==3)throw Error('寄存数量不匹配');
const pickup=await request('提取两件','/api/pickups','POST',{addressId:address.id,storedItems:[{lotId:lots.items[0].id,quantity:2}],requestKey:'http-pickup-'+stamp},user);
await request('审核通过','/api/admin/pickups/'+pickup.id+'/review','POST',{approve:true,reason:'本地审核演练'},admin);
await request('仓库登记模拟物流','/api/admin/pickups/'+pickup.id+'/ship','POST',{carrier:'测试物流',tracking:'LOCAL'+stamp,reason:'本地发货演练，无真实外部发货'},admin);
await request('确认模拟收货','/api/pickups/'+pickup.id+'/receive','POST',undefined,user);
await request('重复收货幂等','/api/pickups/'+pickup.id+'/receive','POST',undefined,user);
const final=await request('核对剩余寄存','/api/mini/storage?limit=20','GET',undefined,user);if(final.items[0].remaining_qty!==1||final.items[0].received_qty!==2)throw Error('最终数量错误');
const wallet=await request('核对积分扣款','/api/wallet','GET',undefined,user);
if(wallet.availableCents!==50000-quote.goodsCents)throw Error('积分重复扣款或余额错误');
const report={time:new Date().toISOString(),environment:'isolated local MySQL, packaged Java 8 JAR',userId:client.user.id,orderId:order.id,pickupId:pickup.id,quoteGoodsCents:quote.goodsCents,finalLot:final.items[0],wallet,results};await fs.writeFile(new URL('./http-purchase.json',import.meta.url),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify({orderId:order.id,pickupId:pickup.id,remaining:final.items[0].remaining_qty,received:final.items[0].received_qty,checks:results.length}));
