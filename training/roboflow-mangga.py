import os
from roboflow import Roboflow

# Kunci dibaca dari variabel lingkungan; jangan menuliskannya di berkas ini.
rf = Roboflow(api_key=os.environ"7D60iToneEPTdEc3kE9n")
project = rf.workspace("absar-rakha").project("mango-ripeness-detection-k52fw")
version = project.version(1)
dataset = version.download("voc")
