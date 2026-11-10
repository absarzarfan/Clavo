source ~/miniforge3/etc/profile.d/conda.sh
conda activate py39
export LD_LIBRARY_PATH=$CONDA_PREFIX/lib:/usr/lib/wsl/lib:$LD_LIBRARY_PATH
export MPLBACKEND=Agg
export CUDA_CACHE_MAXSIZE=2147483648
export TF_FORCE_GPU_ALLOW_GROWTH=true
