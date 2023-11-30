import jpype as _jp
import jvm as _jvm
import numpy as _np
import types as _types
from .. import _proxy

_ia= _jp.JClass('gov.llnl.math.IntegerArray')

#%% Augment int[]
def _shape1(self):
    return (len(self),)

def _unpack1(self):
    return self[:]

def _add1(self, other):
    if isinstance(other, int):
        other=int(other)
    return _ia.add(self, other)

def _iadd1(self, other):
    if isinstance(other, int):
        other=int(other)
    return _ia.addAssign(self, other)

def _fill1(self, other):
    return _ia.fill(self, float(other))


class _Int1ArrayCustomizer(object):
    def canCustomize(self, name, jc):
        if name == 'int[]':
            return True
        return False

    def customize(self, name, jc, bases, members):
        members['shape'] = property(_shape1, None)
        members['unpack'] = _unpack1
        members['__add__']=_add1
        members['__iadd__']=_iadd1
        members['fill']=_fill1

_jp.registerArrayCustomizer(_Int1ArrayCustomizer())

class _NameArrayCustomizer(object):
    def canCustomize(self, name, jc):
        return True

    def customize(self, name, jc, bases, members):
        members['__name__']=name

_jp.registerArrayCustomizer(_NameArrayCustomizer())

#%% Augment int[][]
def _shape2(self):
    d1=len(self)
    if d1==0  or self[0]==None:
        d2=0
    else:
        d2=len(self[0])
    return (d1, d2)

def _unpack2(var, transpose=False):
    d=var.shape
    if transpose:
        out=_np.zeros([d[1], d[0]],dtype=_np.int)
        for i in range(0, d[0]):
            if isinstance(var[i],type(None)):
                continue
            out[:,i]=var[i][:]
        return out
    else:
        out=_np.zeros(d,dtype=_np.int)
        for i in range(0, d[0]):
            if isinstance(var[i],type(None)):
                continue
            out[i]=var[i][:]
        return out

class _Integer2ArrayCustomizer(object):
    def canCustomize(self, name, jc):
        if name == 'int[][]':
            return True
        return False

    def customize(self, name, jc, bases, members):
        members['shape'] = property(_shape2,None)
        members['unpack'] = _unpack2

_jp.registerArrayCustomizer(_Integer2ArrayCustomizer())

#%% Convert implementation
def to(var):
    """ Convert to a java int[] """
    if isinstance(var, _np.ndarray):
        return _jp.JArray(_jp.JInt,1)(var.tolist())
    if isinstance(var, list):
        return _jp.JArray(_jp.JInt,1)(var)
    if _jvm.isArray(var) and _jvm.getTypeName(var)=="int[]":
        return var;
    raise Exception("Can't find conversion for %s to int[]"%(type(var)))


#%% Merge implementations
def cat(*var):
    """ Convert a list of items into java int[]

    Merges all parameters into one dimensional array.

    Args:
        *var (java double[], array, list)

    Returns:
        java int[] array containing all elements.
    """
    # Convert the arrays
    collection=[]
    sz=0
    for entry in var:
        array=to(entry)
        sz=sz+len(array)
        collection.append(array)

    # Trivial case
    if len(collection)==1:
        return collection[0]

    # Merge the arrays
    out=_jvm.new.Ints(sz)
    sz=0
    for entry in collection:
        _proxy.math.IntegerArray.assign(out, sz, entry, 0, len(entry))
        sz=sz+len(entry)
    return out

