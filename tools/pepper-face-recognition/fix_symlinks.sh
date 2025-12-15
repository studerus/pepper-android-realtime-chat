#!/bin/bash
cd /home/nao/python_packages
find . -name '*.cpython-37m-i386-linux-gnu.so' | while read f; do
    newname="${f/-i386-linux-gnu.so/-i686-linux-gnu.so}"
    if [ ! -e "$newname" ]; then
        ln -s "$(basename "$f")" "$newname"
        echo "Created: $newname"
    fi
done
echo "Done!"


