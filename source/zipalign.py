# Builds an APK zip with every stored entry 4-byte aligned (required for resources.arsc on targetSdk 30+).
import zipfile,sys,os
out,root=sys.argv[1],sys.argv[2]
store=lambda p: p=='resources.arsc' or p.endswith('.png') or p=='AndroidManifest.xml' or p.endswith('.dex')
order=['AndroidManifest.xml','resources.arsc','classes.dex']
files=[]
for d,_,fs in os.walk(root):
    for f in fs:
        p=os.path.relpath(os.path.join(d,f),root)
        if p not in order: files.append(p)
files=[p for p in order if os.path.exists(os.path.join(root,p))]+sorted(files)
with zipfile.ZipFile(out,'w') as z:
    for p in files:
        zi=zipfile.ZipInfo(p,(2024,1,1,0,0,0))
        data=open(os.path.join(root,p),'rb').read()
        if store(p):
            zi.compress_type=zipfile.ZIP_STORED
            off=z.fp.tell()+30+len(p.encode())
            zi.extra=b'\0'*((4-off%4)%4)
        else: zi.compress_type=zipfile.ZIP_DEFLATED
        z.writestr(zi,data)
print('zip ok',len(files),'entries')
