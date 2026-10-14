#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = ["numpy==2.4.6", "scipy==1.17.1", "pillow==12.3.0", "rasterio==1.4.4", "pyproj==3.7.2"]
# ///
"""Generate the approved harbour height field from its shoreline and saved generation-11 coast."""
from pathlib import Path
import json,numpy as np
from scipy.interpolate import CubicSpline
from scipy.ndimage import gaussian_filter,distance_transform_edt,map_coordinates,label
from PIL import Image,ImageDraw,ImageFont
P=Path(__file__).resolve().parents[1];data=np.load(P/'tools/data/cape-generation11.npz');h=data['height'].astype(float)/10;xs=data['x'];ys=data['y'];X,Y=np.meshgrid(xs,ys);stepx=xs[1]-xs[0];stepy=ys[0]-ys[1]
def smooth(t):
 t=np.clip(t,0,1);return t*t*(3-2*t)
# Shoreline of a drowned valley: unequal tributary arms separated by long headlands.
shore=np.array([[-24,-19],[-20,-12],[-18,-7],[-16,-5],[-15,-1],[-17,3],[-17,7],[-15.8,10],[-16.2,12],[-15.6,14],[-14.6,15.5],[-14.8,17.5],[-14.3,18],[-14,17.2],[-14.1,15.2],[-13,14],[-13,12],[-12,10],[-12.5,8],[-11,6],[-10,5],[-8,6],[-7.2,7.5],[-7,9.5],[-6,10.5],[-5.6,12],[-4.5,13],[-4.1,12.9],[-4.7,11.4],[-4.9,9.6],[-5.2,8.2],[-4.7,6.8],[-4,5.5],[-4,4],[-1,3],[-.7,4.7],[.5,6.2],[1.2,6.5],[.9,5.5],[.4,4.5],[1,3],[3,2],[4.3,1],[4,-.8],[2.8,-1.3],[1.8,-.5],[1,.7],[-1,.8],[-3,0],[-4,-2],[-6,-3],[-7,-5],[-9,-5.5],[-10,-6],[-11,-9],[-13,-10],[-13,-12],[-16,-17],[-18,-21]])*1000
# Periodic chord-parameterized interpolation avoids circular basin primitives.
closed=np.vstack([shore,shore[0]]);param=np.r_[0,np.cumsum(np.linalg.norm(np.diff(closed,axis=0),axis=1))]
curve=CubicSpline(param,closed,bc_type='periodic')(np.linspace(0,param[-1],2500))
maskim=Image.new('L',(len(xs),len(ys)));d=ImageDraw.Draw(maskim)
d.polygon([((x-xs[0])/stepx,(ys[0]-y)/stepy) for x,y in curve],fill=255)
mask=np.array(maskim)>0
signed=distance_transform_edt(~mask,sampling=(stepy,stepx))-distance_transform_edt(mask,sampling=(stepy,stepx))
# Smaller coastal erosion superimposed on the large valley form.
warpX=X+330*np.sin(X/1900+Y/2400)+75*np.sin(Y/480-X/650)
warpY=Y+280*np.sin(X/2300-Y/1700)+65*np.sin(X/590+Y/410)
signed=map_coordinates(signed,[(ys[0]-warpY)/stepy,(warpX-xs[0])/stepx],order=1,mode='nearest')
# Fill the former bowl with low hills rather than retaining its oval shoreline.
rng=np.random.default_rng(260614);grain=gaussian_filter(rng.normal(size=h.shape),14);grain/=np.std(grain)
hills=24+18*grain+9*np.sin(X/2100+Y/2900)+5*np.sin(X/1000-Y/1700)
hills=np.maximum(8,hills)
oldLabels,_=label(h<0)
row=lambda y:np.argmin(abs(ys-y));col=lambda x:np.argmin(abs(xs-x))
oldLake=oldLabels==oldLabels[row(330),col(2700)]
oldSea=oldLabels==oldLabels[-1,300]
fromLake=distance_transform_edt(~oldLake,sampling=(stepy,stepx))
reform=1-smooth(fromLake/2400)
landbase=np.maximum(h,8)*(1-reform)+hills*reform
# A shallow shelf around the shore, with deeper water following the flooded valley.
depth=8+30*smooth((-signed-350)/2800)
water=-depth*smooth(-signed/420)
land=np.minimum(landbase,np.maximum(0,signed)*.028)
regional=np.where(signed<0,water,land)
# Replace the former lake and its banks completely, blending across dry ground.
weight=np.maximum(1-smooth(fromLake/3000),1-smooth(np.maximum(signed,0)/2500))
new=h*(1-weight)+regional*weight
# The surrounding open sea remains an ocean rather than being raised by the regional design.
new[oldSea]=np.minimum(h[oldSea],regional[oldSea])
# Keep the existing built works grounded and retain the boat's water access.
works=[(0,0,650),(340,-400,650)]
for x,y,r in works:
 protect=1-smooth((np.hypot(X-x,Y-y)-r)/450)
 new=new*(1-protect)+h*protect


from importlib.machinery import SourceFileLoader
encode=SourceFileLoader('earth_import',str(P/'tools/import-earth.py')).load_module().encode
resource=dict(source='Approved branching Cape harbour design',unit=0.1,width=len(xs),height=len(ys),west=float(xs[0]),north=float(ys[0]),dx=float(stepx),dy=float(stepy),data=encode(np.rint(new*10).astype(np.int16)))
(P/'core/src/commonMain/resources/terrain/cape-harbour.json').write_text(json.dumps(resource,separators=(',',':'))+'\n')
