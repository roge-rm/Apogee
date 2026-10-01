# Part generators

These scripts first laid out `bases.json` and `structures.json` in
`core/src/commonMain/resources/parts/`, since base modules and the Cape's buildings
are lots of boxes and cylinders. The JSON has been edited by hand since, so it's the
real source now and running a script would undo those edits.

    python3 art/parts/bases.py core/src/commonMain/resources/parts/bases.json
    python3 art/parts/structures.py core/src/commonMain/resources/parts/structures.json

Changing the part catalogue changes its content hash, which players joining a
server have to match.
