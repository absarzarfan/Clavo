#!/bin/bash
# Jalankan SEKALI di Ubuntu (WSL2) baru. Sekitar 30-50 menit.
cd ~
sudo apt update && sudo apt install -y wget libportaudio2 libsndfile1 libgl1 libusb-1.0-0
wget -nc https://github.com/conda-forge/miniforge/releases/latest/download/Miniforge3-Linux-x86_64.sh
bash Miniforge3-Linux-x86_64.sh -b -p ~/miniforge3
~/miniforge3/bin/conda create -y -n py39 python=3.9
cp "$(dirname "$0")/clavo_env.sh" ~/clavo_env.sh
source ~/clavo_env.sh
pip install tflite_model_maker
pip install pycocotools opencv-python-headless
pip uninstall -y tensorflow && pip install tensorflow==2.8.0
pip install --upgrade numba llvmlite
pip install roboflow
pip install matplotlib-inline==0.1.6
conda install -y -c conda-forge cudatoolkit=11.2 cudnn=8.1.0
echo "SELESAI. Tes: source ~/clavo_env.sh lalu python -c 'import tensorflow as tf; print(tf.config.list_physical_devices(\"GPU\"))'"
