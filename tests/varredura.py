"""Varredura automática do FAMCONT (dados fictícios). Uso: python3 tests/varredura.py [caminho/index.html]
Sai com código 1 se algum teste falhar."""
import asyncio, json, sys, os, tempfile
from playwright.async_api import async_playwright
ROOT=os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC=sys.argv[1] if len(sys.argv)>1 else os.path.join(ROOT,'web','index.html')
CHROME=os.environ.get('CHROME','/usr/bin/google-chrome')
U=1795000000000
def bill(i,name,cat,day=None,amount=None,freq='mensal',status='aberto',paidAt=None,resp='Família'):
    return dict(id='b%d'%i,u=U,name=name,cat=cat,resp=resp,day=day,amount=amount,freq=freq,status=status,paidAt=paidAt,note='',wk=[])
SEED={'app':'FAMCONT','v':1,'data':{'v':1,'cfg':{'renda':10000,'people':['Família','Ana','Beto'],'weekly':[{'name':'Transporte','amount':50}],'u':U},
 'months':{'2026-11':{'u':U,'bills':[bill(1,'Aluguel','moradia',10,2000),bill(2,'Luz','energia',20,180),bill(3,'Internet','telecom',25,120,status='pago',paidAt='2026-11-05'),
   bill(4,'Mesada','filhos',None,10,'semanal'),bill(5,'Viagem','eventos',15,None,'unica','futuro'),bill(6,'Seguro','seguro',None,90)],
  'weeks':[{'id':'w1','u':U,'start':'2026-11-02','end':'2026-11-06','items':[{'id':'i1','u':U,'name':'Transporte','amount':50,'done':True}]},
           {'id':'w2','u':U,'start':'2026-11-09','end':'2026-11-13','items':[{'id':'i2','u':U,'name':'Transporte','amount':50,'done':False}]}]}},
 'fam':[{'id':'f1','u':U,'pessoa':'Parente A','desc':'Compra teste','origem':'Cartão do parente','compra':'2026-08-01','parcela':500,'n':4,'inicio':'2026-09','dia':5,
   'pixTipo':'CPF','pixChave':'529.982.247-25','banco':'Banco X','obs':'','pagos':{'2026-09':True,'2026-10':True,'2026-11':True},'fim':False}]}}
R=[]
def ok(nome,cond,extra=''):
    R.append((bool(cond),nome,extra)); print(('PASS ' if cond else 'FAIL ')+nome+((' · '+str(extra)) if extra and not cond else ''))
async def main():
    html=open(SRC,encoding='utf-8').read()
    page_html=html.replace('<!--SEED-->','<script>window.FAM_SEED='+json.dumps(SEED)+';window.FAM_AUTOSEED=true;</script>') if '<!--SEED-->' in html else html.replace('<script>\n\'use strict\';','<script>window.FAM_SEED='+json.dumps(SEED)+';window.FAM_AUTOSEED=true;</script>\n<script>\n\'use strict\';',1)
    tmp=tempfile.NamedTemporaryFile('w',suffix='.html',delete=False,encoding='utf-8'); tmp.write(page_html); tmp.close()
    url='file://'+tmp.name+'?hoje=2026-11-19'
    img=os.path.join(tempfile.gettempdir(),'famcont_logo_teste.png')
    open(img,'wb').write(bytes.fromhex('89504e470d0a1a0a0000000d4948445200000001000000010806000000'+'1f15c4890000000d49444154789c63f8cfc0f01f0005fe02fea7d6a6d10000000049454e44ae426082'))
    async with async_playwright() as p:
        br=await p.chromium.launch(executable_path=CHROME,args=['--no-sandbox'])
        # ---------- telas em vários tamanhos ----------
        for nome,(w,h) in {'celular compacto 360':(360,780),'Pixel 9 412':(412,915),'celular deitado 915':(915,412),'tablet em pé 800':(800,1280),'tablet deitado 1280':(1280,800)}.items():
            ctx=await br.new_context(viewport={'width':w,'height':h},has_touch=True,locale='pt-BR'); pg=await ctx.new_page(); errs=[]
            pg.on('pageerror',lambda e: errs.append(str(e))); pg.on('console',lambda m: errs.append(m.text) if m.type=='error' and 'raw.githubusercontent' not in m.text and 'Failed to load resource' not in m.text else None)
            await pg.route('**/version.json*',lambda r: r.fulfill(status=200,content_type='application/json',body='{"version":"0.0.0","build":0}'))
            await pg.goto(url); await pg.wait_for_timeout(300); over=[]
            for t in ('meses','contas','familia','semanas','resumo','ajustes'):
                await pg.click(f'.tabs [data-tab={t}]'); await pg.wait_for_timeout(120)
                sw=await pg.evaluate('document.documentElement.scrollWidth')
                if sw>w+1: over.append(f'{t}:{sw}')
            sub=await pg.evaluate("(()=>{const e=document.querySelector('.brand small');return e.scrollWidth<=e.clientWidth+1})()")
            ok(f'[{nome}] 6 abas abrem sem erro de JavaScript',not errs,errs[:2]); ok(f'[{nome}] nada vaza para os lados',not over,over); ok(f'[{nome}] subtítulo Martins&Costa cabe inteiro',sub)
            await ctx.close()
        # ---------- fluxos completos (Pixel 9) ----------
        ctx=await br.new_context(viewport={'width':412,'height':915},has_touch=True,locale='pt-BR',permissions=['clipboard-read','clipboard-write']); pg=await ctx.new_page(); errs=[]
        pg.on('pageerror',lambda e: errs.append(str(e)))
        await pg.route('**/version.json*',lambda r: r.fulfill(status=200,content_type='application/json',body='{"version":"9.9.9","build":999,"notes":"Teste"}'))
        await pg.goto(url); await pg.wait_for_timeout(500)
        ev=lambda js: pg.evaluate(js)
        ok('Abre na tela Meses (mês atual + próximos 6)',await pg.query_selector('.hero') is not None and len(await pg.query_selector_all('.mgrid .mc.fut'))==6)
        prev=await pg.inner_text('.mgrid .mc.fut >> nth=0'); ok('Previsão de dezembro inclui contas fixas e familiares',('Previsão' in prev and '500' in prev),prev.replace(chr(10),' | '))
        await pg.click('.hero'); ok('Tocar no mês atual entra nas contas',await pg.query_selector('.progt') is not None)
        await pg.click('#ml'); ok('Tocar no nome do mês volta para Meses',await pg.query_selector('.hero') is not None)
        await pg.click('.hero')
        ok('Cabeçalho: "Contas da Família Martins&Costa"',(await pg.inner_text('.brand small'))=='Contas da Família Martins&Costa')
        st=await ev("['b1','b2','b3','b5'].map(id=>status(getM('2026-11').bills.find(b=>b.id===id),'2026-11').k)")
        ok('Situações: atrasada (dia 10), vence logo (dia 20), paga, planejada',st==['atrasado','logo','pago','futuro'],st)
        ok('Aviso de nova versão aparece no topo',await pg.query_selector('.banner[data-act=openApk]') is not None)
        p0=await pg.inner_text('.progt'); await pg.click('.chk[data-id=b1]'); p1=await pg.inner_text('.progt'); await pg.click('#toastBtn'); p2=await pg.inner_text('.progt')
        ok('Marcar como pago e Desfazer',p0==p2 and p0!=p1,(p0,p1,p2))
        await pg.click('#fab'); await pg.click('[data-act=saveBill]'); e=await pg.inner_text('#f_err'); ok('Validação: nome obrigatório',e.startswith('Informe'),e)
        await pg.fill('#f_name','Condomínio teste'); await pg.fill('#f_day','40'); await pg.click('[data-act=saveBill]'); e=await pg.inner_text('#f_err'); ok('Validação: dia 1 a 31',('1 a 31' in e),e)
        await pg.fill('#f_day','5'); await pg.fill('#f_amt','1.234,56'); await pg.click('[data-act=saveBill]')
        a=await ev("getM('2026-11').bills.find(b=>b.name==='Condomínio teste').amount"); ok('Incluir conta com valor 1.234,56',a==1234.56,a)
        await pg.click('.bill >> text=Condomínio teste'); await pg.click('[data-act=del]'); await pg.click('[data-act=del]')
        ok('Excluir conta (com confirmação)',await ev("live(getM('2026-11').bills).every(b=>b.name!=='Condomínio teste')"))
        await pg.click('[data-act=goHome]'); await pg.click('.mgrid .mc.fut >> nth=0'); await pg.wait_for_timeout(150)
        ok('Tocar em dezembro abre o mês sozinho',await ev("!!getM('2026-12')&&ui.month==='2026-12'&&ui.tab==='contas'"))
        d=await ev("({n:live(getM('2026-12').bills).length,abertas:live(getM('2026-12').bills).filter(b=>b.status==='aberto').length,w:getM('2026-12').weeks.map(w=>w.start.slice(8)+'-'+w.end.slice(8))})")
        ok('Abrir dezembro copia contas fixas (sem as de uma vez)',d['n']==6 and d['abertas']==5,d)
        ok('Semanas de dezembro corretas',d['w']==['01-04','07-11','14-18','21-25','28-31'],d['w'])
        hi=await pg.inner_text('.famhi .fn'); ok('Destaque contas familiares: dezembro = 1',hi=='1',hi)
        await pg.click('[data-act=next]'); hi=await pg.inner_text('.famhi .fn'); ok('Destaque contas familiares: janeiro = 0',hi=='0',hi)
        await pg.click('[data-act=prev]'); await pg.click('.tabs [data-tab=familia]'); await pg.click('.bill[data-id=f1]')
        ok('Pagar última parcela finaliza o compromisso',await ev("famState(S.fam.find(a=>a.id==='f1')).k")=='pago')
        await pg.click('#toastBtn'); await pg.click('.pxk button'); c=await ev('navigator.clipboard.readText()')
        ok('Copiar PIX copia só os números do CPF',c=='52998224725',c)
        await pg.click('#fab'); await pg.fill('#g_pessoa','Parente B'); await pg.fill('#g_desc','Teste'); await pg.fill('#g_parc','300,00'); await pg.fill('#g_n','3'); await pg.fill('#g_ini','2027-01'); await pg.fill('#g_dia','12')
        await pg.click('#g_pchave'); await pg.keyboard.type('11111111111'); await pg.click('[data-act=saveFam]'); e=await pg.inner_text('#g_err'); ok('PIX: CPF inválido é recusado',('inválido' in e),e)
        for t,v,exp in (('E-mail','nome@email','inválido'),('Chave aleatória','abc','32'),('Celular','2199','DDD')):
            await pg.click(f'[data-act=ptype][data-v="{t}"]'); await pg.fill('#g_pchave',v); await pg.click('[data-act=saveFam]'); e=await pg.inner_text('#g_err'); ok(f'PIX: {t} inválido é recusado',exp in e,e)
        await pg.click('[data-act=ptype][data-v="Celular"]'); await pg.fill('#g_pchave',''); await pg.click('#g_pchave'); await pg.keyboard.type('21998765432'); await pg.click('[data-act=saveFam]')
        f2=await ev("(()=>{const a=famLive().find(a=>a.pessoa==='Parente B');return a?[a.pixTipo,a.pixChave,famKeys(a)]:null})()")
        ok('Novo compromisso com chave Celular e meses gerados',f2==['Celular','(21) 99876-5432',['2027-01','2027-02','2027-03']],f2)
        await pg.click('[data-act=next]'); await pg.click('[data-act=next]'); hi=await ev("famMonth('2027-01').length"); ok('Janeiro passa a mostrar 1 depois de incluir',hi==1,hi)
        # fechamento do mês
        await pg.click('[data-act=goHome]'); await pg.click('.hero'); await pg.click('.tabs [data-tab=resumo]'); await pg.click('.banner[data-act=fech]'); await pg.wait_for_timeout(150)
        t=await pg.inner_text('#sheetCard'); ok('Prévia do fechamento abre no Resumo',('Prévia do fechamento' in t and 'Contas fixas' in t and 'Contas familiares' in t),t[:80])
        await pg.click('.sheet-bg',position={'x':5,'y':5})
        fz=await ev("(()=>{const c=closeState('2026-11');return [c.ok,c.pend]})()"); ok('Fechamento conta as pendências do mês',fz[0]==False and fz[1]>0,fz)
        # imagem do app
        await pg.click('#hdrLogo'); 
        async with pg.expect_file_chooser() as fc: await pg.click('[data-act=logoPick]')
        await (await fc.value).set_files(img); await pg.wait_for_timeout(500)
        ok('Imagem do app aparece no quadrado do topo',await pg.query_selector('#hdrLogo img') is not None)
        # backup: exportar e importar
        txt=await ev('backupText()'); n0=await ev("live(getM('2026-11').bills).length")
        await ev("S.months['2026-11'].bills=[];save(true)"); await ev(f"importText({json.dumps(txt)})"); n1=await ev("live(getM('2026-11').bills).length")
        ok('Backup: salvar e restaurar devolve todas as contas',n0==n1 and n0>0,(n0,n1))
        # Drive (simulado): o app grava o backup sozinho após mudanças
        await ev("window.__w=[];window.FamBridge={writeDrive:t=>{__w.push(t);return 'ok'},driveInfo:()=>JSON.stringify({linked:true,name:'FAMCONT-backup-Pixel-9.json',drive:true}),linkDrive:()=>famDriveLinked('FAMCONT-backup-Pixel-9.json',true),unlinkDrive:()=>{},copy:()=>{},saveFile:()=>'ok',share:()=>{},openUrl:()=>{}}")
        await pg.click('.tabs [data-tab=ajustes]'); await pg.click('[data-act=driveOn]'); await pg.wait_for_timeout(200)
        w1=await ev('__w.length'); ok('Drive: ao ligar, já salva o primeiro backup',w1==1,w1)
        await ev("S.cfg.renda=12345;S.cfg.u=Date.now();save(true)"); await pg.wait_for_timeout(8600); w2=await ev('__w.length')
        last=json.loads(await ev('__w[__w.length-1]'))
        ok('Drive: depois de uma mudança, atualiza sozinho em segundos',w2==2 and last['data']['cfg']['renda']==12345,(w2,))
        ok('Drive: cartão mostra "Ligado"',('Ligado' in (await pg.inner_text('#view'))))
        # sincronização: dois aparelhos convergem
        conv=await ev('''(()=>{const base=JSON.parse(JSON.stringify(S)),A=JSON.parse(JSON.stringify(base)),B=JSON.parse(JSON.stringify(base)),t=Date.now()+5000;
          const ba=A.months['2026-11'].bills.find(b=>b.id==='b2');ba.status='pago';ba.u=t;
          const bb=B.months['2026-11'].bills.find(b=>b.id==='b6');bb.amount=95;bb.u=t+1;B.fam[0].pagos['2026-12']=true;B.fam[0].u=t+2;
          const M1=mergeState(A,B),M2=mergeState(M1,A);const m=M2.months['2026-11'];
          return JSON.stringify(M1)===JSON.stringify(M2)&&m.bills.find(b=>b.id==='b2').status==='pago'&&m.bills.find(b=>b.id==='b6').amount===95&&M2.fam[0].pagos['2026-12']===true})()''')
        ok('Celular + tablet alterando ao mesmo tempo: nada se perde',conv)
        ok('Nenhum erro de JavaScript nos fluxos',not errs,errs[:3])
        await br.close()
    os.unlink(tmp.name)
    falhas=[r for r in R if not r[0]]; print(f'\nRESULTADO: {len(R)-len(falhas)} de {len(R)} testes passaram.')
    sys.exit(1 if falhas else 0)
asyncio.run(main())
