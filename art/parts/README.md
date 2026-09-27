# Part generators

`bases.json` and `structures.json` in `core/src/main/resources/parts/` are
written by these scripts, not by hand. The base modules and the Cape's
buildings are made from lots of boxes and cylinders, and laying them out in
code keeps their sizes consistent. Edit the script, then make the file again:

    python3 art/parts/bases.py core/src/main/resources/parts/bases.json
    python3 art/parts/structures.py core/src/main/resources/parts/structures.json

Changing either one changes the part catalogue's content hash, which players
joining a server have to match.
