# Writes the binary AndroidManifest.xml, the adaptive-icon XML and resources.arsc
# by hand (no aapt2 available). Usage: python3 axml.py <apk staging dir>
import struct,sys,os
A='http://schemas.android.com/apk/res/android'
IDS={'theme':0x01010000,'label':0x01010001,'icon':0x01010002,'name':0x01010003,'exported':0x01010010,'screenOrientation':0x0101001e,
     'configChanges':0x0101001f,'drawable':0x01010199,'minSdkVersion':0x0101020c,'versionCode':0x0101021b,'versionName':0x0101021c,
     'targetSdkVersion':0x01010270,'maxSdkVersion':0x01010271,'hardwareAccelerated':0x010102d3,'roundIcon':0x0101052c,'windowSoftInputMode':0x0101022b,'usesPermissionFlags':0x01010644}
STR,INT,HEX,BOOL,REF=0x03,0x10,0x11,0x12,0x01
NONE=0xffffffff
# app resources: package 0x7f, type 1 = mipmap
R_ICON,R_FG,R_BG=0x7f010000,0x7f010001,0x7f010002

def chunk(t,hs,body,hdr=b''):
    return struct.pack('<HHI',t,hs,8+len(hdr)+len(body))+hdr+body
def pool(strings):
    offs=[];data=b''
    for s in strings:
        offs.append(len(data)); data+=struct.pack('<H',len(s))+s.encode('utf-16le')+b'\0\0'
    while len(data)%4: data+=b'\0'
    hdr=struct.pack('<IIIII',len(strings),0,0,28+4*len(strings),0)
    return chunk(0x0001,28,b''.join(struct.pack('<I',o) for o in offs)+data,hdr)

def axml(tree):
    used=set()
    def names(n):
        for ns,a,t,v in n[1]:
            if ns: used.add(a)
        for c in n[2]: names(c)
    names(tree)
    attrs=sorted(used,key=lambda n:IDS[n]); strings=list(attrs)
    def S(x):
        if x not in strings: strings.append(x)
        return strings.index(x)
    def walk(n):
        S(n[0])
        for ns,a,t,v in n[1]:
            S(a)
            if t==STR: S(v)
        for c in n[2]: walk(c)
    S('android'); S(A); walk(tree)
    body=chunk(0x0100,16,struct.pack('<II',S('android'),S(A)),struct.pack('<II',1,NONE))
    def emit(n):
        nonlocal body
        tag,at,ch=n
        at=sorted(at,key=lambda a:IDS[a[1]] if a[0] else -1)
        ab=b''
        for ns,a,t,v in at:
            raw=S(v) if t==STR else NONE
            val=S(v) if t==STR else (0xffffffff if (t==BOOL and v) else v)
            ab+=struct.pack('<IIIHBBI',S(ns) if ns else NONE,S(a),raw,8,0,t,val)
        body+=chunk(0x0102,16,struct.pack('<IIHHHHHH',NONE,S(tag),20,20,len(at),0,0,0)+ab,struct.pack('<II',1,NONE))
        for c in ch: emit(c)
        body+=chunk(0x0103,16,struct.pack('<II',NONE,S(tag)),struct.pack('<II',1,NONE))
    emit(tree)
    body+=chunk(0x0101,16,struct.pack('<II',S('android'),S(A)),struct.pack('<II',1,NONE))
    out=pool(strings)+chunk(0x0180,8,b''.join(struct.pack('<I',IDS[n]) for n in attrs))+body
    return struct.pack('<HHI',0x0003,8,8+len(out))+out

manifest=('manifest',[(None,'package',STR,'com.wil.strudel37'),(A,'versionCode',INT,7),(A,'versionName',STR,'1.5')],[
  ('uses-sdk',[(A,'minSdkVersion',INT,24),(A,'targetSdkVersion',INT,34)],[]),
  # Bluetooth LE MIDI: legacy permissions up to Android 11, SCAN/CONNECT from Android 12
  ('uses-permission',[(A,'name',STR,'android.permission.BLUETOOTH'),(A,'maxSdkVersion',INT,30)],[]),
  ('uses-permission',[(A,'name',STR,'android.permission.BLUETOOTH_ADMIN'),(A,'maxSdkVersion',INT,30)],[]),
  ('uses-permission',[(A,'name',STR,'android.permission.ACCESS_FINE_LOCATION'),(A,'maxSdkVersion',INT,30)],[]),
  ('uses-permission',[(A,'name',STR,'android.permission.BLUETOOTH_SCAN'),(A,'usesPermissionFlags',HEX,0x10000)],[]),
  ('uses-permission',[(A,'name',STR,'android.permission.BLUETOOTH_CONNECT')],[]),
  ('application',[(A,'label',STR,'Strudel 37'),(A,'icon',REF,R_ICON),(A,'roundIcon',REF,R_ICON),(A,'theme',REF,0x01030007),(A,'hardwareAccelerated',BOOL,1)],[
    ('activity',[(A,'name',STR,'com.wil.strudel37.MainActivity'),(A,'label',STR,'Strudel 37'),(A,'exported',BOOL,1),
                 (A,'screenOrientation',INT,6),(A,'configChanges',HEX,0x4b0),(A,'windowSoftInputMode',HEX,0x10)],[
      ('intent-filter',[],[
        ('action',[(A,'name',STR,'android.intent.action.MAIN')],[]),
        ('category',[(A,'name',STR,'android.intent.category.LAUNCHER')],[])])])])])
adaptive=('adaptive-icon',[],[('background',[(A,'drawable',REF,R_BG)],[]),('foreground',[(A,'drawable',REF,R_FG)],[])])

def config(density=0,sdk=0):
    c=bytearray(64); struct.pack_into('<I',c,0,64)
    struct.pack_into('<H',c,14,density); struct.pack_into('<H',c,24,sdk)
    return bytes(c)
def typechunk(cfg,entries):  # entries: list of (keyIndex, valueStringIndex) or None
    offs=b'';data=b''
    for e in entries:
        if e is None: offs+=struct.pack('<I',NONE); continue
        offs+=struct.pack('<I',len(data))
        data+=struct.pack('<HHI',8,0,e[0])+struct.pack('<HBBI',8,0,STR,e[1])
    hs=20+len(cfg)
    hdr=struct.pack('<BBHII',1,0,0,len(entries),hs+len(offs))+cfg
    return chunk(0x0201,hs,offs+data,hdr)
def arsc():
    files=['res/mipmap/ic_launcher.png','res/mipmap/ic_launcher_fg.png','res/mipmap/ic_launcher_bg.png','res/mipmap-anydpi-v26/ic_launcher.xml']
    keys=['ic_launcher','ic_launcher_fg','ic_launcher_bg']
    tpool=pool(['mipmap']); kpool=pool(keys)
    spec=chunk(0x0202,16,struct.pack('<III',0x0400,0,0),struct.pack('<BBHI',1,0,0,3))
    t_def=typechunk(config(),[(0,0),(1,1),(2,2)])
    t_any=typechunk(config(density=0xFFFE,sdk=26),[(0,3),None,None])
    name=('com.wil.strudel37'.encode('utf-16le')+b'\0'*256)[:256]
    phs=288
    pkg_hdr=struct.pack('<I',0x7f)+name+struct.pack('<IIIII',phs,1,phs+len(tpool),3,0)
    pkg=chunk(0x0200,phs,tpool+kpool+spec+t_def+t_any,pkg_hdr)
    body=pool(files)+pkg
    return chunk(0x0002,12,body,struct.pack('<I',1))

out=sys.argv[1]
os.makedirs(out+'/res/mipmap-anydpi-v26',exist_ok=True)
open(out+'/AndroidManifest.xml','wb').write(axml(manifest))
open(out+'/res/mipmap-anydpi-v26/ic_launcher.xml','wb').write(axml(adaptive))
open(out+'/resources.arsc','wb').write(arsc())
print('manifest, adaptive icon and resources.arsc written')
