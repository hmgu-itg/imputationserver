#!/usr/bin/env bash

# 7z archiving wrapper

SINGULARITY_EXE="/usr/bin/singularity"
CONTAINER_PATH="/home/hdoop/containers/ubuntu_optomics.sif"

function usage () {
    >&2 echo ""
    >&2 echo "Archiving wrapper for 7z"
    >&2 echo ""
    >&2 echo "Usage: $0 -p <password> -o <output.7z> -i <input1> -i <input2> ... "
    >&2 echo ""
    exit 0
}

# join an array
function join_by { local IFS="$1"; shift; echo "$*"; }

declare -a infnames
outfname=""
password=""
while getopts "hp:o:i:" opt; do
    case $opt in
	p)password="$OPTARG";;
	o)outfname="$OPTARG";;
	i)infnames+=("$OPTARG");;
	h)usage;;
	*)usage;;
    esac
done
shift "$((OPTIND-1))"

if [[ -z "$password" ]];then
    >&2 echo "ERROR: no password specified"
    exit 1
fi

if [[ -z "$outfname" ]];then
    >&2 echo "ERROR: no output file name specified"
    exit 1
fi

if [[ "${#infnames[@]}" -eq 0 ]];then
    >&2 echo "ERROR: no input files specified"
    exit 1
fi

# echo $(join_by " " "${infnames[@]}")

"${SINGULARITY_EXE}" exec -B /mnt/storage -B /home/hdoop/container.home/:/home/hdoop/ -B /tmp:/run/user "${CONTAINER_PATH}" 7z a -p"${password}" "-tzip" "${outfname}" $(join_by " " "${infnames[@]}")

exit $?


