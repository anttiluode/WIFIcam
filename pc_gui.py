"""WIFIcam Desktop — Windows/Linux Tk camera control for the WIFIcam Android app.

Install: py -3.13 -m pip install Pillow
Run:     py -3.13 pc_gui.py

Only dependency beyond the Python standard library is Pillow, for JPEG live preview.
Camera access stays on your local Wi-Fi; the app stores the URL in the user's config.
"""
from __future__ import annotations

import io
import json
import os
import queue
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

try:
    from PIL import Image, ImageTk
except ImportError as exc:
    raise SystemExit('WIFIcam GUI needs Pillow. Install it with: python3.13 -m pip install pillow') from exc

APPNAME = 'WIFIcam Desktop'
BASE = Path(os.environ.get('APPDATA', Path.home())) / 'WIFIcam'
CONFIG = BASE / 'desktop.json'
COL = dict(bg='#0b1321', panel='#142237', input='#1d304a', fg='#e7f3ff', muted='#a8bdd2', accent='#48c5fa', warn='#ffba84')
WB_NAMES = {0:'Off',1:'Auto',2:'Incandescent',3:'Fluorescent',4:'Warm fluorescent',5:'Daylight',6:'Cloudy daylight',7:'Twilight',8:'Shade'}


class CameraClient:
    def __init__(self, url: str):
        u = urllib.parse.urlsplit(url.strip())
        if u.scheme != 'http' or not u.hostname or u.username or u.password:
            raise ValueError('Paste the complete http:// URL displayed by WIFIcam on the phone.')
        token = urllib.parse.parse_qs(u.query).get('token', [''])[0]
        if not token:
            raise ValueError('The URL must contain ?token=... from the phone.')
        self.base = f'{u.scheme}://{u.netloc}'
        self.token = token

    def request(self, path, params=None, post=False, timeout=7):
        p = {'token': self.token}
        p.update(params or {})
        url = self.base + path + '?' + urllib.parse.urlencode(p)
        req = urllib.request.Request(url, method='POST' if post else 'GET', headers={'Cache-Control':'no-store'})
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return response.read()

    def json(self, path, params=None, post=False):
        return json.loads(self.request(path, params, post))


class ScrollPanel(tk.Frame):
    def __init__(self, parent):
        super().__init__(parent, bg=COL['bg'])
        canvas = tk.Canvas(self, bg=COL['bg'], bd=0, highlightthickness=0)
        scrollbar = ttk.Scrollbar(self, orient='vertical', command=canvas.yview)
        self.inner = tk.Frame(canvas, bg=COL['bg'])
        self.inner.bind('<Configure>', lambda _: canvas.configure(scrollregion=canvas.bbox('all')))
        self.win = canvas.create_window((0, 0), window=self.inner, anchor='nw')
        canvas.configure(yscrollcommand=scrollbar.set)
        canvas.bind('<Configure>', lambda e: canvas.itemconfigure(self.win, width=e.width))
        canvas.pack(side='left', fill='both', expand=True)
        scrollbar.pack(side='right', fill='y')
        def wheel(e):
            if e.delta: canvas.yview_scroll(int(-e.delta / 120), 'units')
            elif e.num == 4: canvas.yview_scroll(-2, 'units')
            elif e.num == 5: canvas.yview_scroll(2, 'units')
        canvas.bind('<MouseWheel>', wheel)
        self.inner.bind('<MouseWheel>', wheel)


class WIFIcamGUI:
    def __init__(self, root: tk.Tk):
        self.root = root
        root.title(APPNAME + ' — Galaxy Camera2 control')
        root.geometry('1370x870')
        root.minsize(1050, 680)
        root.configure(bg=COL['bg'])
        self.events = queue.Queue()
        self.preview_q = queue.Queue(maxsize=1)
        self.stop = threading.Event()
        self.client: CameraClient | None = None
        self.cameras = []
        self.camera_by_label = {}
        self.capabilities = {}
        self.preview_img = None
        self.full_image = None
        self.preview_last_ok = 0
        self.last_image_size = (0, 0)
        self.pending_send = None
        self.connect_epoch = 0
        self.take_burst = False
        self.config = self.read_config()
        self.folder = Path(self.config.get('folder', str(Path.cwd() / 'captures')))
        self.url = tk.StringVar(value=self.config.get('url', ''))
        self.reveal_url = tk.BooleanVar(value=False)
        self.camera_select = tk.StringVar()
        self.width = tk.StringVar(value='1280')
        self.manualExposure = tk.BooleanVar(value=False)
        self.manualFocus = tk.BooleanVar(value=False)
        self.aeLock = tk.BooleanVar(value=False)
        self.awbLock = tk.BooleanVar(value=False)
        self.torch = tk.BooleanVar(value=False)
        self.iso = tk.StringVar(value='400')
        self.shutterUs = tk.StringVar(value='10000')
        self.zoom = tk.DoubleVar(value=1.0)
        self.focus = tk.DoubleVar(value=0.0)
        self.quality = tk.DoubleVar(value=85)
        self.wb = tk.StringVar(value='Auto (1)')
        self.rot = tk.StringVar(value='0°')
        self.count = tk.StringVar(value='20')
        self.interval = tk.StringVar(value='1.0')
        self.status = tk.StringVar(value='Enter the full URL shown on WIFIcam, then Connect.')
        self.info = tk.StringVar(value='Camera capabilities will appear here.')
        self.build()
        self.root.after(100, self.pump)
        self.root.after(2000, self.poll_state)
        self.root.protocol('WM_DELETE_WINDOW', self.close)
        if self.url.get(): self.root.after(350, self.connect)

    @staticmethod
    def read_config():
        try: return json.loads(CONFIG.read_text(encoding='utf-8'))
        except (OSError, ValueError): return {}

    def save_config(self):
        try:
            BASE.mkdir(parents=True, exist_ok=True)
            CONFIG.write_text(json.dumps({'url': self.url.get().strip(), 'folder': str(self.folder)}, indent=2), encoding='utf-8')
        except OSError as e: self.set_status(f'Could not save preferences: {e}', True)

    def frame(self, parent, title=None):
        f = tk.Frame(parent, bg=COL['panel'], padx=15, pady=13)
        if title: tk.Label(f, text=title, bg=COL['panel'], fg=COL['accent'], font=('Segoe UI', 13, 'bold')).pack(anchor='w', pady=(0,8))
        return f

    def label(self, parent, text):
        w = tk.Label(parent, text=text, fg=COL['muted'], bg=COL['panel'], font=('Segoe UI', 10))
        w.pack(anchor='w', pady=(8,2)); return w

    def button(self, parent, text, command, compact=False):
        b = tk.Button(parent, text=text, command=command, bg=COL['input'], fg=COL['fg'], activebackground='#216184', activeforeground='white', relief='flat', cursor='hand2', padx=12, pady=5 if compact else 9, font=('Segoe UI', 10, 'bold'))
        b.pack(side='left', padx=(0,7), pady=3); return b

    def entry(self, parent, var, width=12):
        e = tk.Entry(parent, textvariable=var, bg=COL['input'], fg=COL['fg'], insertbackground=COL['fg'], relief='flat', width=width, font=('Consolas', 11), bd=5)
        e.pack(fill='x', pady=2)
        e.bind('<Return>', lambda _: self.apply_controls())
        return e

    def check(self, parent, text, variable):
        c = tk.Checkbutton(parent, text=text, variable=variable, command=self.apply_controls, bg=COL['panel'], fg=COL['fg'], activebackground=COL['panel'], activeforeground=COL['accent'], selectcolor=COL['input'], font=('Segoe UI', 10))
        c.pack(anchor='w', pady=3)
        return c

    def slider(self, parent, name, variable, start, end, digits=2, unit=''):
        line=tk.Frame(parent,bg=COL['panel']); line.pack(fill='x', pady=(8,0))
        tk.Label(line,text=name,fg=COL['muted'],bg=COL['panel']).pack(side='left')
        val=tk.Label(line,text='',fg=COL['accent'],bg=COL['panel'],font=('Consolas',11,'bold'))
        val.pack(side='right')
        def show(*_): val.config(text=f'{variable.get():.{digits}f}{unit}')
        variable.trace_add('write',show)
        scale = tk.Scale(parent, from_=start, to=end, variable=variable, resolution=0.01 if digits==2 else 1, orient='horizontal', showvalue=0, highlightthickness=0, bg=COL['panel'], fg=COL['fg'], activebackground=COL['accent'], troughcolor=COL['input'], length=280, command=lambda _: self.schedule_apply())
        scale.bind('<ButtonRelease-1>', lambda _: self.apply_controls())
        scale.pack(fill='x')
        show()
        return scale

    def build(self):
        heading = tk.Frame(self.root,bg=COL['bg'],padx=20,pady=14);heading.pack(fill='x')
        tk.Label(heading,text='WIFIcam',bg=COL['bg'],fg=COL['fg'],font=('Segoe UI',22,'bold')).pack(side='left')
        tk.Label(heading,text='   DESKTOP CAMERA CONTROL',bg=COL['bg'],fg=COL['accent'],font=('Segoe UI',11,'bold')).pack(side='left',pady=8)
        top = self.frame(self.root);top.pack(fill='x',padx=18,pady=(0,12))
        tk.Label(top,text='PHONE CONTROL URL',bg=COL['panel'],fg=COL['muted']).pack(anchor='w')
        row=tk.Frame(top,bg=COL['panel']);row.pack(fill='x')
        self.url_entry = tk.Entry(row,textvariable=self.url,show='•',bg=COL['input'],fg=COL['fg'],insertbackground=COL['fg'],font=('Consolas',11),relief='flat',bd=8)
        self.url_entry.pack(side='left',fill='x',expand=True,padx=(0,12))
        self.button(row,'Connect',self.connect)
        self.button(row,'Show / hide',self.toggle_url,True)
        self.url_entry.bind('<Return>',lambda _: self.connect())
        cols=tk.PanedWindow(self.root,orient='horizontal',sashwidth=8,bg=COL['bg'],bd=0,showhandle=False)
        cols.pack(fill='both',expand=True,padx=18,pady=(0,12))
        left=tk.Frame(cols,bg=COL['bg']);cols.add(left,stretch='always',minsize=460)
        right=ScrollPanel(cols);cols.add(right,stretch='never',width=435,minsize=375)
        pv=self.frame(left,'LIVE PREVIEW');pv.pack(fill='both',expand=True)
        self.preview_box = tk.Label(pv,text='Waiting for camera…',fg=COL['muted'],bg='#02060b',font=('Segoe UI',16))
        self.preview_box.pack(fill='both',expand=True,pady=(0,8))
        tools=tk.Frame(pv,bg=COL['panel']);tools.pack(fill='x')
        self.button(tools,'CAPTURE FULL JPEG',self.capture_photo)
        self.button(tools,'Save preview',self.capture_preview,True)
        self.button(tools,'Refresh preview',self.refresh_preview,True)
        rotate=tk.Frame(pv,bg=COL['panel']);rotate.pack(fill='x',pady=(8,0))
        tk.Label(rotate,text='Display rotation:',bg=COL['panel'],fg=COL['muted']).pack(side='left')
        r=ttk.Combobox(rotate,textvariable=self.rot,values=['0°','90°','180°','270°'],state='readonly',width=7)
        r.pack(side='left',padx=10);r.bind('<<ComboboxSelected>>',lambda _: self.redraw())
        tk.Label(rotate,text='Photo files remain sensor-native.',fg=COL['muted'],bg=COL['panel']).pack(side='left')
        side = right.inner
        sensor=self.frame(side,'CAMERA / LENS');sensor.pack(fill='x',pady=(0,12))
        self.label(sensor,'Select camera (including physical IDs if available)')
        self.camera_box=ttk.Combobox(sensor,textvariable=self.camera_select,state='readonly')
        self.camera_box.pack(fill='x',pady=3)
        self.camera_box.bind('<<ComboboxSelected>>',lambda _: self.update_capabilities())
        lensrow=tk.Frame(sensor,bg=COL['panel']);lensrow.pack(fill='x',pady=(6,0))
        tk.Label(lensrow,text='Preview width',bg=COL['panel'],fg=COL['muted']).pack(side='left')
        self.width_box=ttk.Combobox(lensrow,textvariable=self.width,values=['640','1280','1920'],width=7,state='readonly')
        self.width_box.pack(side='left',padx=8)
        self.button(lensrow,'OPEN CAMERA',self.switch_camera,True)
        tk.Label(sensor,textvariable=self.info,fg=COL['muted'],bg=COL['panel'],justify='left',wraplength=380,font=('Consolas',9)).pack(fill='x',pady=(10,0))
        optical=self.frame(side,'ZOOM / FOCUS');optical.pack(fill='x',pady=(0,12))
        self.zoom_scale=self.slider(optical,'Camera zoom',self.zoom,1,8,2,'×')
        z=tk.Frame(optical,bg=COL['panel']);z.pack(fill='x')
        for v in (0.5,1,2,3,5):self.button(z,f'{v:g}×',lambda v=v:self.set_zoom(v),True)
        self.focus_toggle=self.check(optical,'Manual focus (otherwise autofocus)',self.manualFocus)
        self.focus_scale=self.slider(optical,'Focus distance (0 = infinity)',self.focus,0,10,2,' D')
        foc=tk.Frame(optical,bg=COL['panel']);foc.pack(fill='x')
        self.button(foc,'∞',lambda:self.set_focus(0),True)
        self.button(foc,'1 m',lambda:self.set_focus(1),True)
        self.button(foc,'50 cm',lambda:self.set_focus(2),True)
        self.button(foc,'20 cm',lambda:self.set_focus(5),True)
        exposure=self.frame(side,'MANUAL EXPOSURE');exposure.pack(fill='x',pady=(0,12))
        self.exposure_toggle=self.check(exposure,'Manual ISO + shutter (otherwise auto)',self.manualExposure)
        self.ae_toggle=self.check(exposure,'Lock auto exposure (AE)',self.aeLock)
        fields=tk.Frame(exposure,bg=COL['panel']);fields.pack(fill='x')
        l=tk.Frame(fields,bg=COL['panel']);l.pack(side='left',fill='x',expand=True,padx=(0,8))
        r=tk.Frame(fields,bg=COL['panel']);r.pack(side='left',fill='x',expand=True)
        self.label(l,'ISO');self.iso_entry=self.entry(l,self.iso)
        self.label(r,'Shutter (microseconds)');self.shutter_entry=self.entry(r,self.shutterUs)
        presets=tk.Frame(exposure,bg=COL['panel']);presets.pack(fill='x')
        for us,txt in ((1000,'1 ms'),(5000,'5 ms'),(10000,'10 ms'),(33333,'33 ms')):
            self.button(presets,txt,lambda us=us:self.set_shutter(us),True)
        self.button(exposure,'APPLY ISO / EXPOSURE',self.apply_controls)
        color=self.frame(side,'WHITE BALANCE / LIGHT');color.pack(fill='x',pady=(0,12))
        self.label(color,'White balance mode')
        self.wb_box=ttk.Combobox(color,textvariable=self.wb,values=[f'{v} ({k})' for k,v in WB_NAMES.items()],state='readonly')
        self.wb_box.pack(fill='x');self.wb_box.bind('<<ComboboxSelected>>',lambda _:self.apply_controls())
        self.awb_toggle=self.check(color,'Lock automatic white balance',self.awbLock)
        self.torch_toggle=self.check(color,'Torch / continuous LED',self.torch)
        self.quality_scale=self.slider(color,'Preview JPEG quality',self.quality,30,100,0,'')
        batch=self.frame(side,'CAPTURE / BATCH');batch.pack(fill='x',pady=(0,12))
        tk.Label(batch,textvariable=tk.StringVar(value=''),bg=COL['panel']).pack_forget()
        self.folder_label=tk.Label(batch,text=str(self.folder),fg=COL['muted'],bg=COL['panel'],wraplength=370,justify='left')
        self.folder_label.pack(anchor='w')
        choose=tk.Frame(batch,bg=COL['panel']);choose.pack(fill='x')
        self.button(choose,'Choose save folder',self.choose_folder,True)
        row=tk.Frame(batch,bg=COL['panel']);row.pack(fill='x')
        a=tk.Frame(row,bg=COL['panel']);a.pack(side='left',fill='x',expand=True,padx=(0,10))
        b=tk.Frame(row,bg=COL['panel']);b.pack(side='left',fill='x',expand=True)
        self.label(a,'Photos in burst');self.entry(a,self.count,5)
        self.label(b,'Seconds between');self.entry(b,self.interval,6)
        burstline=tk.Frame(batch,bg=COL['panel']);burstline.pack(fill='x',pady=(6,0))
        self.burst_button=self.button(burstline,'START BURST',self.capture_burst)
        self.button(burstline,'Stop after this photo',self.cancel_burst,True)
        footer=tk.Frame(self.root,bg=COL['panel'],padx=15,pady=9);footer.pack(fill='x',side='bottom',padx=18,pady=(0,10))
        tk.Label(footer,textvariable=self.status,fg=COL['accent'],bg=COL['panel'],anchor='w',wraplength=1300,justify='left').pack(fill='x')
        style=ttk.Style()
        if 'clam' in style.theme_names(): style.theme_use('clam')
        style.configure('TCombobox',fieldbackground=COL['input'],background=COL['input'],foreground=COL['fg'],arrowcolor=COL['fg'])

    def toggle_url(self):
        self.reveal_url.set(not self.reveal_url.get())
        self.url_entry.configure(show='' if self.reveal_url.get() else '•')

    def set_status(self, s, error=False):
        self.status.set(('ERROR: ' if error else '') + str(s))

    def spawn(self, func, callback=None):
        def work():
            try: out=(True,func())
            except Exception as e: out=(False,e)
            self.events.put(('task',callback,out))
        threading.Thread(target=work,daemon=True).start()

    def connect(self):
        try: client=CameraClient(self.url.get())
        except ValueError as e: self.set_status(e,True);return
        self.connect_epoch += 1
        epoch=self.connect_epoch
        self.client=client
        self.set_status('Connecting to phone and discovering cameras…')
        self.save_config()
        def job(): return client.json('/api/cameras'),client.json('/api/state')
        def complete(result):
            if epoch!=self.connect_epoch:return
            cams,state=result
            self.cameras=cams
            self.camera_by_label={c.get('label',c.get('id','?')):c for c in cams}
            self.camera_box['values']=list(self.camera_by_label)
            selected=next((c for c in cams if c.get('id')==state.get('selected')),None)
            if not selected and cams:selected=cams[0]
            if selected:self.camera_select.set(selected.get('label',selected['id']))
            self.width.set(str(state.get('requestedWidth',1280)))
            self.read_state(state,force=True)
            self.update_capabilities()
            self.set_status(f"Connected — {len(cams)} camera entries. {state.get('status','?')}  /  Preview starting.")
            threading.Thread(target=self.preview_loop,args=(client,epoch),daemon=True).start()
        self.spawn(job,complete)

    def poll_state(self):
        if self.stop.is_set(): return
        if self.client:
            client = self.client
            epoch = self.connect_epoch
            def show(state):
                if epoch != self.connect_epoch: return
                self.set_status(f"{state.get('status', '?')}  |  {state.get('videoWidth',0)}×{state.get('videoHeight',0)} preview  |  {state.get('photoWidth',0)}×{state.get('photoHeight',0)} still  |  frames {state.get('frames',0)}" + (f"  |  {state['error']}" if state.get('error') else ''))
            # Do not overwrite capture/burst messages while a batch is running.
            if not self.take_burst:
                # A slow/unavailable phone must not spawn unbounded background probes.
                if not getattr(self, '_state_busy', False):
                    self._state_busy = True
                    def state_job():
                        try: return client.json('/api/state')
                        finally: self.events.put(('progress_probe_done', None, None))
                    self.spawn(state_job, show)
        self.root.after(3000, self.poll_state)

    def preview_loop(self,client,epoch):
        while not self.stop.is_set() and self.connect_epoch==epoch:
            try:
                start=time.monotonic()
                data=client.request('/snapshot',timeout=5)
                if not self.preview_q.full():self.preview_q.put_nowait((epoch,data))
                time.sleep(max(.05,.22-(time.monotonic()-start)))
            except (urllib.error.URLError,OSError):time.sleep(.9)
            except Exception:time.sleep(1)

    def pump(self):
        if self.stop.is_set():return
        for _ in range(25):
            try:kind,cb,result=self.events.get_nowait()
            except queue.Empty:break
            if kind=='task':
                ok,value=result
                if ok:
                    if cb:
                        try:cb(value)
                        except Exception as e:self.set_status(str(e),True)
                else:self.set_status(f'{type(value).__name__}: {value}',True)
            elif kind=='progress':self.set_status(str(result))
            elif kind=='progress_probe_done':self._state_busy=False
        try:
            _,data=self.preview_q.get_nowait()
            self.full_image=Image.open(io.BytesIO(data)).convert('RGB')
            self.preview_last_ok=time.monotonic()
            self.redraw()
        except queue.Empty:pass
        except Exception as e:self.set_status(f'Preview JPEG: {e}',True)
        self.root.after(100,self.pump)

    def redraw(self):
        if self.full_image is None:return
        try:
            img=self.full_image.copy()
            degrees=int(self.rot.get().replace('°',''))
            if degrees:img=img.rotate(-degrees,expand=True)
            w=max(350,self.preview_box.winfo_width()-20)
            h=max(250,self.preview_box.winfo_height()-20)
            img.thumbnail((w,h),Image.Resampling.LANCZOS)
            self.preview_img=ImageTk.PhotoImage(img)
            self.preview_box.configure(image=self.preview_img,text='')
        except Exception:pass

    def refresh_preview(self):
        if not self.client:return
        self.spawn(lambda:self.client.request('/snapshot'),lambda b:self.display_once(b))

    def display_once(self,data):
        self.full_image=Image.open(io.BytesIO(data)).convert('RGB');self.redraw()

    def update_capabilities(self):
        cam=self.camera_by_label.get(self.camera_select.get())
        if not cam:return
        self.capabilities=cam
        zmin=float(cam.get('zoomMin',1));zmax=max(zmin,float(cam.get('zoomMax',1)))
        fmax=max(0,float(cam.get('focusMax',0)))
        self.zoom_scale.config(from_=zmin,to=zmax)
        self.focus_scale.config(from_=0,to=max(0.01,fmax),state='normal' if fmax>0 else 'disabled')
        # Controls left at another lens's range must not block updates.
        if self.zoom.get()<zmin or self.zoom.get()>zmax: self.zoom.set(max(zmin,min(zmax,self.zoom.get())))
        if self.focus.get()>fmax: self.focus.set(fmax)
        self.iso.set(str(max(int(cam.get('isoMin',1)),min(int(cam.get('isoMax',100000)),int(self.iso.get())))))
        self.shutterUs.set(str(max(int(cam.get('shutterMinUs',1)),min(int(cam.get('shutterMaxUs',1000000)),int(self.shutterUs.get())))))
        self.exposure_toggle.config(state='normal' if cam.get('manualSensor') else 'disabled')
        self.focus_toggle.config(state='normal' if fmax>0 else 'disabled')
        self.torch_toggle.config(state='normal' if cam.get('flash') else 'disabled')
        supported=set(cam.get('awbModes',[]))
        self.wb_box['values']=[f'{v} ({k})' for k,v in WB_NAMES.items() if k in supported]
        if supported and self.current_wb() not in supported:self.wb.set(f'{WB_NAMES.get(1,"Auto")} (1)')
        self.info.set(f"ID: {cam.get('id')}  |  ISO {cam.get('isoMin','?')}–{cam.get('isoMax','?')}\nExposure {cam.get('shutterMinUs','?')}–{cam.get('shutterMaxUs','?')} µs\nFocus ≤ {fmax:g} D  |  zoom {zmin:g}–{zmax:g}×\nManual sensor: {'YES' if cam.get('manualSensor') else 'not exposed'}; flash: {'yes' if cam.get('flash') else 'no'}\nPhysical lens access depends on Samsung firmware.")

    def read_state(self,s,force=False):
        for k in ('manualExposure','manualFocus','aeLock','awbLock','torch'):
            getattr(self,k).set(bool(s.get(k,False)))
        for k in ('iso','shutterUs'):
            getattr(self,k).set(str(s.get(k,getattr(self,k).get())))
        for k in ('zoom','focus','quality'):
            getattr(self,k).set(float(s.get(k,getattr(self,k).get())))
        awb=int(s.get('awbMode',1))
        self.wb.set(f'{WB_NAMES.get(awb,"Mode")} ({awb})')

    def current_wb(self):
        try:return int(self.wb.get().rsplit('(',1)[-1].rstrip(')'))
        except ValueError:return 1

    def controls(self):
        cam=self.capabilities
        def num(var,name,low,high,convert):
            try:v=convert(var.get())
            except ValueError:raise ValueError(f'{name} must be a number.') from None
            if not low<=v<=high:raise ValueError(f'{name} must be between {low} and {high}.')
            return v
        iso=num(self.iso,'ISO',int(cam.get('isoMin',1)),int(cam.get('isoMax',100000)),int)
        shutter=num(self.shutterUs,'Shutter (µs)',int(cam.get('shutterMinUs',1)),int(cam.get('shutterMaxUs',1000000)),int)
        return {'manualExposure':str(self.manualExposure.get()).lower(),
                'manualFocus':str(self.manualFocus.get()).lower(),
                'aeLock':str(self.aeLock.get()).lower(),
                'awbLock':str(self.awbLock.get()).lower(),
                'torch':str(self.torch.get()).lower(),
                'iso':iso,'shutterUs':shutter,
                'zoom':round(self.zoom.get(),3),'focus':round(self.focus.get(),3),
                'quality':int(self.quality.get()),'awbMode':self.current_wb()}

    def schedule_apply(self):
        if self.pending_send:self.root.after_cancel(self.pending_send)
        self.pending_send=self.root.after(220,self.apply_controls)

    def apply_controls(self):
        if self.pending_send:self.root.after_cancel(self.pending_send);self.pending_send=None
        if not self.client or not self.capabilities:return
        try:values=self.controls()
        except ValueError as e:self.set_status(e,True);return
        client=self.client
        self.spawn(lambda:client.json('/api/set',values,True),lambda _:self.set_status('Controls queued on phone (Camera2).'))

    def set_zoom(self,zoom):
        c=self.capabilities
        self.zoom.set(min(max(float(c.get('zoomMin',1)),zoom),float(c.get('zoomMax',8))))
        self.apply_controls()

    def set_focus(self,distance):
        if float(self.capabilities.get('focusMax',0))<=0:return
        self.manualFocus.set(True)
        self.focus.set(min(distance,float(self.capabilities.get('focusMax',distance))))
        self.apply_controls()

    def set_shutter(self,us):
        self.manualExposure.set(True)
        self.shutterUs.set(str(us))
        self.apply_controls()

    def switch_camera(self):
        if not self.client:return
        cam=self.camera_by_label.get(self.camera_select.get())
        if not cam:return
        client=self.client
        self.set_status(f"Opening camera {cam['id']}…")
        self.spawn(lambda:client.json('/api/camera',{'id':cam['id'],'width':self.width.get()},True),lambda _:self.set_status('Camera switch queued. Wait for preview.'))

    def choose_folder(self):
        folder=filedialog.askdirectory(initialdir=str(self.folder),title='Choose folder for WIFIcam photos')
        if folder:
            self.folder=Path(folder);self.folder_label.configure(text=str(self.folder));self.save_config()

    def get_filename(self,prefix):
        self.folder.mkdir(parents=True,exist_ok=True)
        return self.folder/f'{prefix}_{datetime.now().strftime("%Y%m%d_%H%M%S_%f")}.jpg'

    def capture_photo(self):
        if not self.client:return
        client=self.client
        try:path=self.get_filename('full')
        except OSError as e:self.set_status(e,True);return
        self.set_status('Capturing full-resolution JPEG…')
        def job():
            data=client.request('/photo',timeout=15)
            path.write_bytes(data)
            return path,len(data)
        self.spawn(job,lambda v:self.set_status(f'Saved {v[0]}  ({v[1]:,} bytes)'))

    def capture_preview(self):
        if not self.client:return
        client=self.client
        try:path=self.get_filename('preview')
        except OSError as e:self.set_status(e,True);return
        def job():
            data=client.request('/snapshot',timeout=8);path.write_bytes(data)
            return path
        self.spawn(job,lambda p:self.set_status(f'Saved {p}'))

    def capture_burst(self):
        if not self.client or self.take_burst:return
        try:
            count=int(self.count.get());interval=float(self.interval.get())
            if not 1<=count<=1000 or not 0<=interval<=60:raise ValueError()
            self.folder.mkdir(parents=True,exist_ok=True)
        except (ValueError,OSError):self.set_status('Burst: count 1–1000; interval 0–60 seconds; writable output folder required.',True);return
        client=self.client;folder=self.folder
        self.take_burst=True;self.stop_burst=threading.Event()
        self.burst_button.config(state='disabled')
        def work():
            saved=0
            try:
                for n in range(count):
                    if self.stop_burst.is_set() or self.stop.is_set():break
                    t0=time.monotonic()
                    data=client.request('/photo',timeout=15)
                    path=folder/f'burst_{datetime.now().strftime("%Y%m%d_%H%M%S_%f")}_{n:04d}.jpg'
                    path.write_bytes(data);saved+=1
                    self.events.put(('progress',None,f'Burst {saved}/{count}: {path.name}'))
                    remaining=interval-(time.monotonic()-t0)
                    if remaining>0:self.stop_burst.wait(remaining)
                self.events.put(('task',self.finish_burst,(True,f'Burst finished: {saved} JPEGs saved in {folder}')))
            except Exception as e:self.events.put(('task',self.fail_burst,(True,str(e))))
        threading.Thread(target=work,daemon=True).start()

    def finish_burst(self,msg):
        self.take_burst=False
        self.burst_button.config(state='normal')
        self.set_status(msg)

    def fail_burst(self,msg):
        self.take_burst=False
        self.burst_button.config(state='normal')
        self.set_status(msg,True)

    def cancel_burst(self):
        if self.take_burst:self.stop_burst.set();self.set_status('Stopping burst after current capture…')

    def close(self):
        self.stop.set()
        self.connect_epoch+=1
        if self.take_burst:self.stop_burst.set()
        self.root.destroy()


def main():
    root=tk.Tk()
    WIFIcamGUI(root)
    root.mainloop()


if __name__=='__main__':main()
