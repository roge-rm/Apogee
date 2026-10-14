#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = ["numpy==2.4.6", "rasterio==1.4.4", "scipy==1.17.1", "pyproj==3.7.2"]
# ///
"""Import Beck et al. 1991–2020 observed climate as Terra's continuous moisture field."""
import sys,json,hashlib
from pathlib import Path
from importlib.machinery import SourceFileLoader
import numpy as np,rasterio
from scipy.ndimage import distance_transform_edt,gaussian_filter
root=Path(__file__).resolve().parents[1]
encode=SourceFileLoader('earth_import',str(root/'tools/import-earth.py')).load_module().encode
source=Path(sys.argv[1])
with rasterio.open(source) as raster:
 climate=raster.read(1,out_shape=(720,1440))
nearest=distance_transform_edt(climate==0,return_distances=False,return_indices=True)
climate=climate[tuple(nearest)]
wet=np.array([.55,.88,.78,.60,.12,.18,.40,.43,.53,.56,.58,.72,.72,.74,.55,.57,.59,.63,.63,.63,.66,.66,.66,.67,.67,.67,.65,.65,.65,.50,.50])
values=gaussian_filter(wet[climate],.7,mode=('nearest','wrap'))
grid=dict(unit=.001,width=1440,height=720,west=-179.875,north=89.875,dx=.25,dy=.25,periodic=True,data=encode(np.rint(values*1000).astype(np.int16)))
grid.update(source='Beck et al. (2023), observed Koppen-Geiger climate 1991–2020',citation='doi:10.1038/s41597-023-02549-6',license='CC BY 4.0',sourceSHA256=hashlib.sha256(source.read_bytes()).hexdigest())
(root/'core/src/commonMain/resources/terrain/earth-moisture.json').write_text(json.dumps(grid,separators=(',',':'))+'\n')
