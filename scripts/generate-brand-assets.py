from pathlib import Path
import json, subprocess,colorsys
ROOT=Path(__file__).resolve().parent.parent
D=ROOT/"branding/final"
D.mkdir(parents=True,exist_ok=True)
(ROOT/"frontend/public/brand").mkdir(parents=True,exist_ok=True)
cat='M52 158C35 133 51 100 80 88C109 76 142 87 156 108L168 91L180 111L196 99V126C214 136 211 160 190 167H86C71 167 59 165 52 158Z'
# True transparent counters; no painted white shapes or surface-dependent masks.
eye='M171.6 140.4Q181 147.5 188.7 139.1A2 2 0 0 0 185.3 136.9Q180.5 140.5 174.4 137.6A2 2 0 0 0 171.6 140.4Z'
tail='M59.2 133.9C50.7 153.4 72.1 159.3 99.1 158C124.5 156.9 141 145.9 141 128C141 110 122 101.6 105 108.2A3 3 0 0 0 107 113.8C122 108.4 135 115.1 135 128C135 142.1 119.5 151.1 98.9 152C71.9 153.3 59.3 146.6 64.8 136.1A3 3 0 0 0 59.2 133.9Z'
def book(x,y,right,small=False):
 # Outer and inner boundaries include the original six-unit contour.
 h=16 if small else 18
 return f'M{x} {y-3}H{right}Q{right+3} {y-3} {right+3} {y}V{y+h}Q{right+3} {y+h+3} {right} {y+h+3}H{x}Q{x-1} {y+h+3} {x-2} {y+h+2}Q{x-16} {y+h/2} {x-2} {y-2}Q{x-1} {y-3} {x} {y-3}Z M{x+1} {y+3}H{right-3}V{y+h-3}H{x+1}Q{x-7} {y+h/2} {x+1} {y+3}Z'
def shape(lines=False,small=False):
 # Standard artwork keeps a one-unit edge-to-edge gap; favicon enlarges all gaps equally to six units.
 dy=-12 if small else -7
 paths=[{'d':cat+' '+eye+' '+tail,'transform':f'translate(0 {dy})'}]
 for x,y,r in ((43,164,212),(52,192 if small else 189,221),(35,220 if small else 214,204)):
  paths.append({'d':book(x,y,r,small)})
  if lines:
   paths.append({'d':f'M{x+4} {y+7.5}H{r-14}A1.5 1.5 0 0 1 {r-14} {y+10.5}H{x+4}A1.5 1.5 0 0 1 {x+4} {y+7.5}Z'})
 return paths
shapes={'empty':shape(),'lined':shape(True),'small':shape(small=True),'small-lined':shape(True,small=True)}
def markup(paths,fill):return ''.join(f'<path fill="{fill}" fill-rule="evenodd" d="{p["d"]}"'+(f' transform="{p["transform"]}"' if 'transform' in p else '')+'/>' for p in paths)
def svg(paths,fill,bg=None):return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="16 56 216 188"><title>EPL Sync</title>'+ (f'<rect x="16" y="56" width="216" height="188" fill="{bg}"/>' if bg else '')+markup(paths,fill)+'</svg>'
for name,paths in shapes.items():
 for variant,color in [('black','#161916'),('white','#ffffff'),('green','#294536'),('adaptive','currentColor')]:
  (ROOT/f'frontend/public/brand/eplsync-{name}-{variant}.svg').write_text(svg(paths,color))
# Editable local masters retained for future design work (branding is ignored by Git).
for name,key in [('sin-linea','empty'),('con-linea','lined'),('sin-linea-small','small'),('con-linea-small','small-lined')]:
 master=D/f'eplsync-{name}.svg'
 if not master.exists(): master.write_text(svg(shapes[key],'#294536'))
# Favicon tile uses the simplified drawing, filled holes remain transparent to the tile.
favicon='<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256"><title>EPL Sync</title><rect width="256" height="256" rx="52" fill="#294536"/><g transform="translate(12 -10) scale(.92)">'+markup(shapes['small'],'#a8d9bb')+'</g></svg>'
(ROOT/'frontend/public/favicon.svg').write_text(favicon)
(ROOT/'frontend/public/brand/eplsync-app-icon.svg').write_text(favicon)
(ROOT/'frontend/src/components/logoArtwork.ts').write_text('// Shared geometry for the theme-aware EPL Sync logo. Counters are transparent.\nexport const logoArtwork = '+json.dumps(shapes,indent=2)+' as const;\n')
# Twenty theme previews using the application's actual HSL formulas.
palettes=[('Verde',150),('Azul',215),('Violeta',265),('Rosa',335),('Naranja',30),('Cian',185),('Índigo',240),('Púrpura',285),('Lima',85),('Amarillo',48)]
def rgb(h,s,l):return '#'+''.join(f'{round(c*255):02x}' for c in colorsys.hls_to_rgb(h/360,l/100,s/100))
def lum(c):
 a=[int(c[i:i+2],16)/255 for i in (1,3,5)];a=[v/12.92 if v<=.04045 else ((v+.055)/1.055)**2.4 for v in a];return sum(v*w for v,w in zip(a,(.2126,.7152,.0722)))
parts=['<svg xmlns="http://www.w3.org/2000/svg" width="1600" height="1060"><rect width="1600" height="1060" fill="#eeeee9"/><text x="40" y="56" font-family="DejaVu Sans" font-size="30" fill="#202824">EPL Sync · diez paletas, dos modos</text>'];report=[]
for i,(name,h) in enumerate(palettes):
 x=40+(i%5)*312;y=92+(i//5)*472
 for j,mode in enumerate(('light','dark')):
  bg=rgb(h,35,90) if mode=='light' else rgb(h,23,20);fg=rgb(h,55,29) if mode=='light' else rgb(h,40,76)
  contrast=(max(lum(bg),lum(fg))+.05)/(min(lum(bg),lum(fg))+.05);report.append({'palette':name,'scheme':mode,'background':bg,'foreground':fg,'contrast':round(contrast,2)})
  yy=y+j*222
  parts.append(f'<rect x="{x}" y="{yy}" width="290" height="210" rx="12" fill="{bg}"/><svg x="{x+59}" y="{yy+12}" width="172" height="150" viewBox="16 56 216 188">{markup(shapes["empty"],fg)}</svg><text x="{x+14}" y="{yy+191}" font-family="DejaVu Sans" font-size="16" fill="{fg}">{name} · {"claro" if j==0 else "oscuro"}</text>')
parts.append('</svg>');(D/'paletas.svg').write_text(''.join(parts));(D/'contraste.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
subprocess.run(['rsvg-convert',str(D/'paletas.svg'),'-o',str(D/'paletas.png')],check=True)
print('Minimum contrast:', min(r['contrast'] for r in report))
