/*
 * Copyright 2014 Guidewire Software, Inc.
 */

package gw.internal.gosu.parser;

import gw.lang.reflect.java.IJavaClassParameterizedType;
import gw.lang.reflect.java.IJavaClassType;
import gw.lang.reflect.java.asm.AsmType;
import gw.lang.reflect.java.asm.IAsmType;
import gw.lang.reflect.module.IModule;

import java.util.List;

public class AsmParameterizedTypeJavaClassParameterizedType extends AsmTypeJavaClassType implements IJavaClassParameterizedType {

  public AsmParameterizedTypeJavaClassParameterizedType(IAsmType parameterizedType) {
    super(parameterizedType);
  }

  @Override
  public IJavaClassType[] getActualTypeArguments() {
    List<AsmType> rawTypes = getType().getTypeParameters();
    IJavaClassType[] types = new IJavaClassType[rawTypes.size()];
    for (int i = 0; i < rawTypes.size(); i++) {
      types[i] = AsmTypeJavaClassType.createType( rawTypes.get( i ));
    }
    return types;
  }

  @Override
  public IJavaClassType getConcreteType() {
    return AsmTypeJavaClassType.createType( getType().getRawType());
  }

  @Override
  public String getSimpleName() {
    return getType().getSimpleName();
  }
}
