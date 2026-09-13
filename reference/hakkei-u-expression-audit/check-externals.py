import UnityPy
x=UnityPy.load('E:/yukkuri/资料/原作参考/eightSight.v8.3/ゆっくり八景U_Data/level0')
for a in x.files.values():
 if hasattr(a,'externals'):print([(i+1,e.path) for i,e in enumerate(a.externals)])
