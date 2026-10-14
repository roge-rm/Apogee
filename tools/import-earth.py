#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = ["numpy==2.4.6", "rasterio==1.4.4", "scipy==1.17.1", "pyproj==3.7.2"]
# ///
"""Build Terra's compact Earth relief and Arctic ice resources; see docs/terra-terrain.md."""
import argparse, base64, hashlib, json
from pathlib import Path
import numpy as np
import rasterio
from rasterio.warp import reproject, Resampling
from rasterio.transform import from_origin
from scipy.ndimage import distance_transform_edt, map_coordinates
from pyproj import Transformer


def encode(grid):
    a = grid.astype(np.int32)
    prediction = np.zeros_like(a)
    prediction[:, 1:] += a[:, :-1]
    prediction[1:, :] += a[:-1, :]
    prediction[1:, 1:] -= a[:-1, :-1]
    delta = (a - prediction).ravel()
    codes = ((delta << 1) ^ (delta >> 31)).astype(np.uint32)
    out = bytearray()
    for value in codes:
        n = int(value)
        while n >= 128:
            out.append((n & 127) | 128)
            n >>= 7
        out.append(n)
    return base64.b64encode(out).decode('ascii')


def relief(source, south, north, west, east, step):
    if abs(step - 1 / 60) < 1e-10:
        # Keep source pixel centres for the finest patches, preserving narrow summit samples.
        south += step / 2; north -= step / 2
        west += step / 2; east -= step / 2
    w = round((east - west) / step) + 1
    h = round((north - south) / step) + 1
    grid = np.empty((h, w), dtype=np.float32)
    # Samples sit ON the stated meridians/parallels, not half a cell away.
    reproject(rasterio.band(source, 1), grid, src_transform=source.transform, src_crs=source.crs,
              dst_transform=from_origin(west - step / 2, north + step / 2, step, step),
              dst_crs='EPSG:4326', resampling=Resampling.bilinear, num_threads=2)
    if west == -180 and east == 180:
        grid[:, -1] = grid[:, 0]
        grid[0, :] = np.mean(grid[1, :])
        grid[-1, :] = np.mean(grid[-2, :])
    grid = np.rint(grid).astype(np.int16)
    return dict(south=south, north=north, west=west, east=east, step=step, width=w, height=h, data=encode(grid))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('etopo', type=Path)
    parser.add_argument('arctic', type=Path)
    args = parser.parse_args()
    out = Path(__file__).resolve().parents[1] / 'core/src/commonMain/resources/terrain'
    out.mkdir(parents=True, exist_ok=True)
    with rasterio.open(args.etopo) as source:
        grids = [relief(source, -90, 90, -180, 180, .1)]
        # Finer data for major ranges and their valleys. Fade back into the global grid at the edges.
        for bounds in [(35, 71, -12, 45), (26, 38, 70, 105), (-56, 12, -82, -62),
                       (30, 72, -150, -100), (-15, 15, 25, 45)]:
            grids.append(relief(source, *bounds, 1 / 60 if bounds == (26, 38, 70, 105) else .05))
        grids.append(relief(source, 43, 49, 4, 17, 1 / 60))
    relief_data = dict(source='NOAA ETOPO 2022 v1 60s ice surface', earthRadius=6371000, citation='NOAA NCEI (2022), ETOPO 2022, doi:10.25921/fd45-gt74', sourceSHA256=hashlib.sha256(args.etopo.read_bytes()).hexdigest(), grids=grids)
    (out / 'earth-relief.json').write_text(json.dumps(relief_data, separators=(',', ':')) + '\n')
    with rasterio.open(args.arctic) as source:
        extent = source.read(1)
        ice = extent == 1
        # Land/coast is not an open-water ice edge. Terrain's mapped dry ground wins there.
        closed = ice | (extent >= 253)
        signed = distance_transform_edt(closed) - distance_transform_edt(~ice)
        signed *= 25_000.0
        longitude, latitude = np.meshgrid(np.linspace(-180, 180, 1441), np.linspace(90, 40, 201))
        transformer = Transformer.from_crs('EPSG:4326', source.crs, always_xy=True)
        x, y = transformer.transform(longitude, latitude)
        col = (x - source.transform.c) / 25_000.0 - .5
        row = (source.transform.f - y) / 25_000.0 - .5
        values = map_coordinates(signed, [row, col], order=1, mode='constant', cval=-3_000_000)
        values = np.rint(values / 100).clip(-32767, 32767).astype(np.int16)
        values[:, -1] = values[:, 0]
        values[0, :] = values[0, 0]
    grid = dict(south=40, north=90, west=-180, east=180, step=.25, width=1441, height=201, data=encode(values))
    (out / 'arctic-ice.json').write_text(json.dumps(dict(source='NSIDC Sea Ice Index v4 March 2025 extent', units=100, citation='Fetterer et al. (2025), Sea Ice Index v4, doi:10.7265/a98x-0f50; Northern Hemisphere March 2025 monthly extent', sourceSHA256=hashlib.sha256(args.arctic.read_bytes()).hexdigest(), grid=grid), separators=(',', ':')) + '\n')
    for path in out.glob('*.json'):
        print(path.name, path.stat().st_size, hashlib.sha256(path.read_bytes()).hexdigest())
    for path in [args.etopo, args.arctic]:
        print(path.name, hashlib.sha256(path.read_bytes()).hexdigest())

if __name__ == '__main__': main()
