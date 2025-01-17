/*
 * Copyright 2023 The Fury authors
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fury.builder;

import static io.fury.codegen.CodeGenerator.getPackage;
import static io.fury.codegen.Expression.Invoke.inlineInvoke;
import static io.fury.codegen.Expression.Reference.fieldRef;
import static io.fury.codegen.ExpressionUtils.eq;
import static io.fury.codegen.ExpressionUtils.neq;
import static io.fury.serializer.CodegenSerializer.LazyInitBeanSerializer;
import static io.fury.type.TypeUtils.CLASS_TYPE;
import static io.fury.type.TypeUtils.COLLECTION_TYPE;
import static io.fury.type.TypeUtils.MAP_TYPE;
import static io.fury.type.TypeUtils.OBJECT_TYPE;
import static io.fury.type.TypeUtils.PRIMITIVE_BOOLEAN_TYPE;
import static io.fury.type.TypeUtils.PRIMITIVE_BYTE_TYPE;
import static io.fury.type.TypeUtils.PRIMITIVE_DOUBLE_TYPE;
import static io.fury.type.TypeUtils.PRIMITIVE_FLOAT_TYPE;
import static io.fury.type.TypeUtils.PRIMITIVE_INT_TYPE;
import static io.fury.type.TypeUtils.PRIMITIVE_LONG_TYPE;
import static io.fury.type.TypeUtils.PRIMITIVE_SHORT_TYPE;
import static io.fury.type.TypeUtils.PRIMITIVE_VOID_TYPE;
import static io.fury.type.TypeUtils.SET_TYPE;
import static io.fury.type.TypeUtils.getElementType;
import static io.fury.type.TypeUtils.getRawType;
import static io.fury.type.TypeUtils.isBoxed;
import static io.fury.type.TypeUtils.isPrimitive;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.TypeToken;
import io.fury.Fury;
import io.fury.codegen.CodeGenerator;
import io.fury.codegen.CodegenContext;
import io.fury.codegen.Expression;
import io.fury.codegen.Expression.Assign;
import io.fury.codegen.Expression.Cast;
import io.fury.codegen.Expression.If;
import io.fury.codegen.Expression.Invoke;
import io.fury.codegen.Expression.ListExpression;
import io.fury.codegen.Expression.Reference;
import io.fury.codegen.Expression.Return;
import io.fury.codegen.ExpressionOptimizer;
import io.fury.codegen.ExpressionUtils;
import io.fury.codegen.ExpressionVisitor;
import io.fury.collection.Tuple2;
import io.fury.memory.MemoryBuffer;
import io.fury.resolver.ClassInfo;
import io.fury.resolver.ClassInfoCache;
import io.fury.resolver.ClassResolver;
import io.fury.resolver.RefResolver;
import io.fury.serializer.CollectionSerializers.CollectionSerializer;
import io.fury.serializer.CompatibleSerializer;
import io.fury.serializer.MapSerializers.MapSerializer;
import io.fury.serializer.ObjectSerializer;
import io.fury.serializer.Serializer;
import io.fury.serializer.Serializers;
import io.fury.serializer.StringSerializer;
import io.fury.type.FinalObjectTypeStub;
import io.fury.type.TypeUtils;
import io.fury.util.ReflectionUtils;
import io.fury.util.StringUtils;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Function;

/**
 * Generate sequential read/write code for java serialization to speed up performance. It also
 * reduces space overhead introduced by aligning. Codegen only for time-consuming field, others
 * delegate to fury.
 */
public abstract class BaseObjectCodecBuilder extends CodecBuilder {
  public static final String BUFFER_NAME = "buffer";
  public static final String REF_RESOLVER_NAME = "refResolver";
  public static final String CLASS_RESOLVER_NAME = "classResolver";
  public static final String POJO_CLASS_TYPE_NAME = "classType";
  public static final String STRING_SERIALIZER_NAME = "strSerializer";
  private static final TypeToken<?> CLASS_RESOLVER_TYPE_TOKEN = TypeToken.of(ClassResolver.class);
  private static final TypeToken<?> STRING_SERIALIZER_TYPE_TOKEN =
      TypeToken.of(StringSerializer.class);
  private static final TypeToken<?> SERIALIZER_TYPE = TypeToken.of(Serializer.class);

  protected final Reference refResolverRef;
  protected final Reference classResolverRef =
      fieldRef(CLASS_RESOLVER_NAME, CLASS_RESOLVER_TYPE_TOKEN);
  protected final Fury fury;
  protected final Reference stringSerializerRef;
  private final Map<Class<?>, Reference> serializerMap = new HashMap<>();
  private final Map<Class<?>, Reference> classInfoMap = new HashMap<>();
  protected final Class<?> parentSerializerClass;
  private final Map<String, String> jitCallbackUpdateFields;

  public BaseObjectCodecBuilder(TypeToken<?> beanType, Fury fury, Class<?> parentSerializerClass) {
    super(new CodegenContext(), beanType);
    this.fury = fury;
    this.parentSerializerClass = parentSerializerClass;
    addCommonImports();
    ctx.reserveName(REF_RESOLVER_NAME);
    ctx.reserveName(CLASS_RESOLVER_NAME);
    TypeToken<?> refResolverTypeToken = TypeToken.of(fury.getRefResolver().getClass());
    refResolverRef = fieldRef(REF_RESOLVER_NAME, refResolverTypeToken);
    Expression refResolverExpr =
        new Invoke(furyRef, "getRefResolver", TypeToken.of(RefResolver.class));
    ctx.addField(
        ctx.type(refResolverTypeToken),
        REF_RESOLVER_NAME,
        new Cast(refResolverExpr, refResolverTypeToken));
    Expression classResolverExpr =
        inlineInvoke(furyRef, "getClassResolver", CLASS_RESOLVER_TYPE_TOKEN);
    ctx.addField(ctx.type(CLASS_RESOLVER_TYPE_TOKEN), CLASS_RESOLVER_NAME, classResolverExpr);
    ctx.reserveName(STRING_SERIALIZER_NAME);
    stringSerializerRef = fieldRef(STRING_SERIALIZER_NAME, STRING_SERIALIZER_TYPE_TOKEN);
    ctx.addField(
        ctx.type(TypeToken.of(StringSerializer.class)),
        STRING_SERIALIZER_NAME,
        inlineInvoke(furyRef, "getStringSerializer", CLASS_RESOLVER_TYPE_TOKEN));
    jitCallbackUpdateFields = new HashMap<>();
  }

  public String codecClassName(Class<?> beanClass) {
    String name = ReflectionUtils.getClassNameWithoutPackage(beanClass).replace("$", "_");
    StringBuilder nameBuilder = new StringBuilder(name);
    if (fury.trackingRef()) {
      // Generated classes are different when referenceTracking is switched.
      // So we need to use a different name.
      nameBuilder.append("FuryRef");
    } else {
      nameBuilder.append("Fury");
    }
    nameBuilder.append(codecSuffix()).append("Codec");
    nameBuilder.append('_').append(fury.getConfig().getConfigHash());
    String classUniqueId = CodeGenerator.getClassUniqueId(beanClass);
    if (StringUtils.isNotBlank(classUniqueId)) {
      nameBuilder.append('_').append(classUniqueId);
    }
    return nameBuilder.toString();
  }

  public String codecQualifiedClassName(Class<?> beanClass) {
    String pkg = getPackage(beanClass);
    if (StringUtils.isNotBlank(pkg)) {
      return pkg + "." + codecClassName(beanClass);
    } else {
      return codecClassName(beanClass);
    }
  }

  protected abstract String codecSuffix();

  <T> T visitFury(Function<Fury, T> function) {
    return fury.getJITContext().asyncVisitFury(function);
  }

  @Override
  public String genCode() {
    ctx.setPackage(getPackage(beanClass));
    String className = codecClassName(beanClass);
    ctx.setClassName(className);
    // don't addImport(beanClass), because user class may name collide.
    ctx.extendsClasses(ctx.type(parentSerializerClass));
    ctx.reserveName(POJO_CLASS_TYPE_NAME);
    ctx.addField(ctx.type(Fury.class), FURY_NAME);
    Expression encodeExpr = buildEncodeExpression();
    Expression decodeExpr = buildDecodeExpression();
    String constructorCode =
        StringUtils.format(
            ""
                + "super(${fury}, ${cls});\n"
                + "this.${fury} = ${fury};\n"
                + "${fury}.getClassResolver().setSerializerIfAbsent(${cls}, this);\n",
            "fury",
            FURY_NAME,
            "cls",
            POJO_CLASS_TYPE_NAME);

    ctx.clearExprState();
    String encodeCode = encodeExpr.genCode(ctx).code();
    encodeCode = ctx.optimizeMethodCode(encodeCode);
    ctx.clearExprState();
    String decodeCode = decodeExpr.genCode(ctx).code();
    decodeCode = ctx.optimizeMethodCode(decodeCode);
    ctx.overrideMethod(
        "write",
        encodeCode,
        void.class,
        MemoryBuffer.class,
        BUFFER_NAME,
        Object.class,
        ROOT_OBJECT_NAME);
    ctx.overrideMethod("read", decodeCode, Object.class, MemoryBuffer.class, BUFFER_NAME);
    registerJITNotifyCallback();
    ctx.addConstructor(constructorCode, Fury.class, "fury", Class.class, POJO_CLASS_TYPE_NAME);
    return ctx.genCode();
  }

  protected void registerJITNotifyCallback() {
    // build encode/decode expr before add constructor to fill up jitCallbackUpdateFields.
    if (!jitCallbackUpdateFields.isEmpty()) {
      StringJoiner stringJoiner = new StringJoiner(", ", "registerJITNotifyCallback(this,", ");\n");
      for (Map.Entry<String, String> entry : jitCallbackUpdateFields.entrySet()) {
        stringJoiner.add("\"" + entry.getKey() + "\"");
        stringJoiner.add(entry.getValue());
      }
      // add this code after field serialization initialization to avoid
      // it overrides field updates by this callback.
      ctx.addInitCode(stringJoiner.toString());
    }
  }

  /**
   * Add common imports to reduce generated code size to speed up jit. Since user class are
   * qualified, there won't be any conflict even if user class has the same name as fury classes.
   *
   * @see CodeGenerator#getClassUniqueId
   */
  protected void addCommonImports() {
    ctx.addImports(List.class, Map.class, Set.class);
    ctx.addImports(Fury.class, MemoryBuffer.class, fury.getRefResolver().getClass());
    ctx.addImports(ClassInfo.class, ClassInfoCache.class, ClassResolver.class);
    ctx.addImport(Generated.class);
    ctx.addImports(LazyInitBeanSerializer.class, Serializers.EnumSerializer.class);
    ctx.addImports(Serializer.class, StringSerializer.class);
    ctx.addImports(ObjectSerializer.class, CompatibleSerializer.class);
    ctx.addImports(CollectionSerializer.class, MapSerializer.class, ObjectSerializer.class);
  }

  protected Expression serializeFor(
      Expression inputObject, Expression buffer, TypeToken<?> typeToken) {
    return serializeFor(inputObject, buffer, typeToken, false);
  }

  /**
   * Returns an expression that serialize an nullable <code>inputObject</code> to <code>buffer
   * </code>.
   */
  protected Expression serializeFor(
      Expression inputObject,
      Expression buffer,
      TypeToken<?> typeToken,
      boolean generateNewMethod) {
    // access rawType without jit lock to reduce lock competition.
    Class<?> rawType = getRawType(typeToken);
    if (visitFury(fury -> fury.getClassResolver().needToWriteRef(rawType))) {
      return new If(
          ExpressionUtils.not(writeRefOrNull(buffer, inputObject)),
          serializeForNotNull(inputObject, buffer, typeToken, generateNewMethod));
    } else {
      // if typeToken is not final, ref tracking of subclass will be ignored too.
      if (typeToken.isPrimitive()) {
        return serializeForNotNull(inputObject, buffer, typeToken, generateNewMethod);
      }
      Expression action =
          new ListExpression(
              new Invoke(
                  buffer,
                  "writeByte",
                  new Expression.Literal(Fury.REF_VALUE_FLAG, PRIMITIVE_BYTE_TYPE)),
              serializeForNotNull(inputObject, buffer, typeToken, generateNewMethod));
      return new If(
          ExpressionUtils.eqNull(inputObject),
          new Invoke(
              buffer, "writeByte", new Expression.Literal(Fury.NULL_FLAG, PRIMITIVE_BYTE_TYPE)),
          action);
    }
  }

  protected Expression writeRefOrNull(Expression buffer, Expression object) {
    return inlineInvoke(refResolverRef, "writeRefOrNull", PRIMITIVE_BOOLEAN_TYPE, buffer, object);
  }

  protected Expression serializeForNotNull(
      Expression inputObject, Expression buffer, TypeToken<?> typeToken) {
    return serializeForNotNull(inputObject, buffer, typeToken, false);
  }

  /**
   * Returns an expression that serialize an not null <code>inputObject</code> to <code>buffer
   * </code>.
   */
  private Expression serializeForNotNull(
      Expression inputObject,
      Expression buffer,
      TypeToken<?> typeToken,
      boolean generateNewMethod) {
    Class<?> clz = getRawType(typeToken);
    if (isPrimitive(clz) || isBoxed(clz)) {
      // for primitive, inline call here to avoid java boxing, rather call corresponding serializer.
      if (clz == byte.class || clz == Byte.class) {
        return new Invoke(buffer, "writeByte", inputObject);
      } else if (clz == boolean.class || clz == Boolean.class) {
        return new Invoke(buffer, "writeBoolean", inputObject);
      } else if (clz == char.class || clz == Character.class) {
        return new Invoke(buffer, "writeChar", inputObject);
      } else if (clz == short.class || clz == Short.class) {
        return new Invoke(buffer, "writeShort", inputObject);
      } else if (clz == int.class || clz == Integer.class) {
        String func = fury.compressNumber() ? "writeVarInt" : "writeInt";
        return new Invoke(buffer, func, inputObject);
      } else if (clz == long.class || clz == Long.class) {
        String func = fury.compressNumber() ? "writeVarLong" : "writeLong";
        return new Invoke(buffer, func, inputObject);
      } else if (clz == float.class || clz == Float.class) {
        return new Invoke(buffer, "writeFloat", inputObject);
      } else if (clz == double.class || clz == Double.class) {
        return new Invoke(buffer, "writeDouble", inputObject);
      } else {
        throw new IllegalStateException("impossible");
      }
    } else {
      if (clz == String.class) {
        return fury.getStringSerializer().writeStringExpr(stringSerializerRef, buffer, inputObject);
      }
      Expression action;
      // this is different from ITERABLE_TYPE in RowCodecBuilder. In row-format we don't need to
      // ensure
      // class consistence, we only need to ensure interface consistence. But in java serialization,
      // we need to ensure class consistence.
      if (useCollectionSerialization(typeToken)) {
        action = serializeForCollection(buffer, inputObject, typeToken, generateNewMethod);
      } else if (useMapSerialization(typeToken)) {
        action = serializeForMap(buffer, inputObject, typeToken, generateNewMethod);
      } else {
        action = serializeForNotNullObject(inputObject, buffer, typeToken);
      }
      return action;
    }
  }

  protected boolean useCollectionSerialization(TypeToken<?> typeToken) {
    return COLLECTION_TYPE.isSupertypeOf(typeToken);
  }

  protected boolean useMapSerialization(TypeToken<?> typeToken) {
    return MAP_TYPE.isSupertypeOf(typeToken);
  }

  /**
   * Whether the provided type should be taken as final. Although the <code>clz</code> can be final,
   * the method can still return false. For example, we return false in meta share mode to write
   * class defs for the non-inner final types.
   */
  protected abstract boolean isFinal(Class<?> clz);

  protected Expression serializeForNotNullObject(
      Expression inputObject, Expression buffer, TypeToken<?> typeToken) {
    Class<?> clz = getRawType(typeToken);

    if (isFinal(clz)) {
      Expression serializer = getOrCreateSerializer(clz);
      return new Invoke(serializer, "write", buffer, inputObject);
    } else {
      return writeForNotNullNonFinalObject(inputObject, buffer, typeToken);
    }
  }

  // Note that `CompatibleCodecBuilder` may mark some final objects as non-final.
  protected Expression writeForNotNullNonFinalObject(
      Expression inputObject, Expression buffer, TypeToken<?> typeToken) {
    Class<?> clz = getRawType(typeToken);
    Expression clsExpr = new Invoke(inputObject, "getClass", "cls", CLASS_TYPE);
    ListExpression writeClassAndObject = new ListExpression();
    Tuple2<Reference, Boolean> classInfoRef = addClassInfoField(clz);
    Expression classInfo = classInfoRef.f0;
    if (classInfoRef.f1) {
      writeClassAndObject.add(
          new If(
              neq(new Invoke(classInfo, "getCls", CLASS_TYPE), clsExpr),
              new Assign(
                  classInfo,
                  inlineInvoke(classResolverRef, "getClassInfo", classInfoTypeToken, clsExpr))));
    }
    writeClassAndObject.add(
        fury.getClassResolver().writeClassExpr(classResolverRef, buffer, classInfo));
    writeClassAndObject.add(
        new Invoke(
            inlineInvoke(classInfo, "getSerializer", SERIALIZER_TYPE),
            "write",
            PRIMITIVE_VOID_TYPE,
            buffer,
            inputObject));
    return ExpressionOptimizer.invokeGenerated(
        ctx,
        ImmutableSet.of(buffer, inputObject),
        writeClassAndObject,
        "writeClassAndObject",
        false);
  }

  /**
   * Returns a serializer expression which will be used to call write/read method to avoid virtual
   * methods calls in most situations.
   */
  protected Expression getOrCreateSerializer(Class<?> cls) {
    Preconditions.checkArgument(isFinal(cls), cls);
    Reference serializerRef = serializerMap.get(cls);
    if (serializerRef == null) {
      // potential recursive call for seq codec generation is handled in `getSerializerClass`.
      Class<? extends Serializer> serializerClass =
          visitFury(f -> f.getClassResolver().getSerializerClass(cls));
      Preconditions.checkNotNull(serializerClass, "Unsupported for class " + cls);
      ClassLoader beanClassClassLoader =
          beanClass.getClassLoader() == null
              ? Thread.currentThread().getContextClassLoader()
              : beanClass.getClassLoader();
      try {
        beanClassClassLoader.loadClass(serializerClass.getName());
      } catch (ClassNotFoundException e) {
        // If `cls` is loaded in another class different from `beanClassClassLoader`,
        // then serializerClass is loaded in another class different from `beanClassClassLoader`.
        serializerClass = LazyInitBeanSerializer.class;
      }
      if (serializerClass == LazyInitBeanSerializer.class
          || serializerClass == ObjectSerializer.class
          || serializerClass == CompatibleSerializer.class) {
        // field init may get jit serializer, which will cause cast exception if not use base type.
        serializerClass = Serializer.class;
      }
      TypeToken<? extends Serializer> serializerTypeToken = TypeToken.of(serializerClass);
      Expression.Literal clzLiteral = new Expression.Literal(ctx.type(cls) + ".class");
      // Don't invoke `Serializer.newSerializer` here, since it(ex. ObjectSerializer) may set itself
      // as global serializer, which overwrite serializer updates in jit callback.
      Expression newSerializerExpr =
          inlineInvoke(classResolverRef, "getSerializer", SERIALIZER_TYPE, clzLiteral);
      String name = ctx.newName(StringUtils.uncapitalize(serializerClass.getSimpleName()));
      // It's ok it jit already finished and this method return false, in such cases
      // `serializerClass` is already jit generated class.
      boolean hasJITResult = fury.getJITContext().hasJITResult(cls);
      if (hasJITResult) {
        jitCallbackUpdateFields.put(name, ctx.type(cls) + ".class");
        ctx.addField(
            ctx.type(Serializer.class), name, new Cast(newSerializerExpr, SERIALIZER_TYPE), false);
        serializerRef = new Reference(name, SERIALIZER_TYPE, false);
      } else {
        ctx.addField(
            ctx.type(serializerClass),
            name,
            new Cast(newSerializerExpr, serializerTypeToken),
            true);
        serializerRef = fieldRef(name, serializerTypeToken);
      }
      serializerMap.put(cls, serializerRef);
    }
    return serializerRef;
  }

  /**
   * The boolean value in tuple indicates whether the classinfo needs update.
   *
   * @return false for tuple field1 if the classinfo doesn't need update.
   */
  protected Tuple2<Reference, Boolean> addClassInfoField(Class<?> cls) {
    Expression classInfoExpr;
    boolean needUpdate = !ReflectionUtils.isFinal(cls);
    if (!needUpdate) {
      Reference classInfoRef = classInfoMap.get(cls);
      if (classInfoRef != null) {
        return Tuple2.of(classInfoRef, false);
      }
      Expression clsExpr = new Expression.Literal(cls, CLASS_TYPE);
      classInfoExpr = inlineInvoke(classResolverRef, "getClassInfo", classInfoTypeToken, clsExpr);
      // Use `ctx.freshName(cls)` to avoid wrong name for arr type.
      String name = ctx.newName(ctx.newName(cls) + "ClassInfo");
      ctx.addField(ctx.type(ClassInfo.class), name, classInfoExpr, true);
      classInfoRef = fieldRef(name, classInfoTypeToken);
      classInfoMap.put(cls, classInfoRef);
      return Tuple2.of(classInfoRef, false);
    } else {
      classInfoExpr = inlineInvoke(classResolverRef, "nilClassInfo", classInfoTypeToken);
      String name = ctx.newName(StringUtils.uncapitalize(cls.getSimpleName()) + "ClassInfo");
      ctx.addField(ctx.type(ClassInfo.class), name, classInfoExpr, false);
      // Can't use fieldRef, since the field is not final.
      return Tuple2.of(new Reference(name, classInfoTypeToken), true);
    }
  }

  protected Reference addClassInfoCacheField(Class<?> cls) {
    Preconditions.checkArgument(!Modifier.isFinal(cls.getModifiers()), cls);
    Expression classInfoCacheExpr =
        inlineInvoke(classResolverRef, "nilClassInfoCache", classInfoCacheTypeToken);
    String name = ctx.newName(cls, "ClassInfoCache");
    ctx.addField(ctx.type(ClassInfoCache.class), name, classInfoCacheExpr, true);
    // The class info field read only once, no need to shallow.
    return new Reference(name, classInfoCacheTypeToken);
  }

  protected Expression readClassInfo(Class<?> cls, Expression buffer) {
    return readClassInfo(cls, buffer, true);
  }

  protected Expression readClassInfo(Class<?> cls, Expression buffer, boolean inlineReadClassInfo) {
    if (Modifier.isFinal(cls.getModifiers())) {
      Reference classInfoRef = addClassInfoField(cls).f0;
      if (inlineReadClassInfo) {
        return inlineInvoke(
            classResolverRef, "readClassInfo", classInfoTypeToken, buffer, classInfoRef);
      } else {
        return new Invoke(
            classResolverRef, "readClassInfo", classInfoTypeToken, buffer, classInfoRef);
      }
    }
    Reference classInfoCacheRef = addClassInfoCacheField(cls);
    if (inlineReadClassInfo) {
      return inlineInvoke(
          classResolverRef, "readClassInfo", classInfoTypeToken, buffer, classInfoCacheRef);
    } else {
      return new Invoke(
          classResolverRef, "readClassInfo", classInfoTypeToken, buffer, classInfoCacheRef);
    }
  }

  /**
   * Return an expression to write a collection to <code>buffer</code>. This expression can have
   * better efficiency for final element type. For final element type, it doesn't have to write
   * class info, no need to forward to <code>fury</code>.
   *
   * @param generateNewMethod Generated code for nested container will be greater than 325 bytes,
   *     which is not possible for inlining, and if code is bigger, jit compile may also be skipped.
   */
  protected Expression serializeForCollection(
      Expression buffer, Expression collection, TypeToken<?> typeToken, boolean generateNewMethod) {
    TypeToken<?> elementType = getElementType(typeToken);
    ListExpression actions = new ListExpression();
    Expression serializer;
    Class<?> clz = getRawType(typeToken);
    Expression clsExpr;
    if (isFinal(clz)) {
      serializer = getOrCreateSerializer(clz);
      clsExpr = new Expression.Literal(clz, CLASS_TYPE);
    } else {
      ListExpression writeClassAction = new ListExpression();
      Tuple2<Reference, Boolean> classInfoRef = addClassInfoField(clz);
      Expression classInfo = classInfoRef.f0;
      clsExpr = new Invoke(collection, "getClass", "cls", CLASS_TYPE);
      writeClassAction.add(
          new If(
              neq(new Invoke(classInfo, "getCls", CLASS_TYPE), clsExpr),
              new Assign(
                  classInfo,
                  inlineInvoke(classResolverRef, "getClassInfo", classInfoTypeToken, clsExpr))));
      writeClassAction.add(
          fury.getClassResolver().writeClassExpr(classResolverRef, buffer, classInfo));
      serializer = new Invoke(classInfo, "getSerializer", "serializer", SERIALIZER_TYPE, false);
      serializer = new Cast(serializer, TypeToken.of(CollectionSerializer.class));
      writeClassAction.add(serializer, new Return(serializer));
      // Spit this into a separate method to avoid method too big to inline.
      serializer =
          ExpressionOptimizer.invokeGenerated(
              ctx,
              ImmutableSet.of(buffer, clsExpr),
              writeClassAction,
              "writeCollectionClassInfo",
              false);
    }
    Expression hookWrite;
    if (getRawType(collection.type()).isAssignableFrom(ArrayList.class)
        || ArrayList.class.isAssignableFrom(getRawType(collection.type()))) {
      // if type hierarchy no interact, cast will compile error.
      hookWrite =
          new If(
              eq(clsExpr, new Expression.Literal(ArrayList.class, CLASS_TYPE)),
              writeListCodegen(buffer, collection, serializer, elementType),
              writeCollectionCodegen(buffer, collection, serializer, elementType),
              false);
    } else {
      hookWrite = writeCollectionCodegen(buffer, collection, serializer, elementType);
    }
    Expression write =
        new If(
            inlineInvoke(serializer, "supportCodegenHook", PRIMITIVE_BOOLEAN_TYPE),
            hookWrite,
            new Invoke(serializer, "write", buffer, collection));
    actions.add(write);
    if (generateNewMethod) {
      return ExpressionOptimizer.invokeGenerated(
          ctx, ImmutableSet.of(buffer, collection), actions, "writeCollection", false);
    }
    return actions;
  }

  protected Expression writeCollectionCodegen(
      Expression buffer, Expression collection, Expression serializer, TypeToken<?> elementType) {
    Invoke size = new Invoke(collection, "size", PRIMITIVE_INT_TYPE);
    Invoke writeSize = new Invoke(buffer, "writePositiveVarInt", size);
    Invoke writeHeader = new Invoke(serializer, "writeHeader", buffer, collection);
    ExpressionVisitor.ExprHolder exprHolder = ExpressionVisitor.ExprHolder.of("buffer", buffer);
    Expression.ForEach writeElements =
        new Expression.ForEach(
            collection,
            (i, value) -> {
              boolean generateNewMethod =
                  useCollectionSerialization(elementType) || useMapSerialization(elementType);
              return serializeFor(value, exprHolder.get("buffer"), elementType, generateNewMethod);
            });
    return new ListExpression(writeSize, writeHeader, writeElements);
  }

  protected Expression writeListCodegen(
      Expression buffer, Expression collection, Expression serializer, TypeToken<?> elementType) {
    Expression list =
        new Cast(collection, TypeUtils.arrayListOf(getRawType(elementType)), "arrList");
    Expression start = new Expression.Literal(0, PRIMITIVE_INT_TYPE);
    Expression step = new Expression.Literal(1, PRIMITIVE_INT_TYPE);
    Invoke size = new Invoke(collection, "size", PRIMITIVE_INT_TYPE);
    Invoke writeSize = new Invoke(buffer, "writePositiveVarInt", size);
    Invoke writeHeader = new Invoke(serializer, "writeHeader", buffer, collection);
    ExpressionVisitor.ExprHolder exprHolder =
        ExpressionVisitor.ExprHolder.of("buffer", buffer, "list", list);
    Expression.ForLoop writeElements =
        new Expression.ForLoop(
            start,
            size,
            step,
            i -> {
              Invoke elem = new Invoke(exprHolder.get("list"), "get", OBJECT_TYPE, false, i);
              boolean generateNewMethod =
                  useCollectionSerialization(elementType) || useMapSerialization(elementType);
              return serializeFor(
                  tryCastIfPublic(elem, elementType),
                  exprHolder.get("buffer"),
                  elementType,
                  generateNewMethod);
            });
    return new ListExpression(list, writeSize, writeHeader, writeElements);
  }

  protected Expression tryInlineCast(Expression expression, TypeToken targetType) {
    return tryCastIfPublic(expression, targetType, true);
  }

  protected Expression tryCastIfPublic(Expression expression, TypeToken targetType) {
    return tryCastIfPublic(expression, targetType, false);
  }

  protected Expression tryCastIfPublic(
      Expression expression, TypeToken targetType, boolean inline) {
    if (getRawType(targetType) == FinalObjectTypeStub.class) {
      // final field doesn't exist in this class, skip cast.
      return expression;
    }
    if (inline) {
      if (ReflectionUtils.isPublic(targetType)
          && !expression.type().wrap().isSubtypeOf(targetType.wrap())) {
        return new Cast(expression, targetType);
      } else {
        return expression;
      }
    }
    return tryCastIfPublic(expression, targetType, "castedValue");
  }

  protected Expression tryCastIfPublic(
      Expression expression, TypeToken targetType, String valuePrefix) {
    if (ReflectionUtils.isPublic(targetType)
        && !expression.type().wrap().isSubtypeOf(targetType.wrap())) {
      return new Cast(expression, targetType, valuePrefix);
    }
    return expression;
  }

  /**
   * Return an expression to write a map to <code>buffer</code>. This expression can have better
   * efficiency for final key/value type. For final key/value type, it doesn't have to write class
   * info, no need to forward to <code>fury</code>.
   */
  protected Expression serializeForMap(
      Expression buffer, Expression map, TypeToken<?> typeToken, boolean generateNewMethod) {
    Tuple2<TypeToken<?>, TypeToken<?>> keyValueType = TypeUtils.getMapKeyValueType(typeToken);
    TypeToken<?> keyType = keyValueType.f0;
    TypeToken<?> valueType = keyValueType.f1;
    ListExpression actions = new ListExpression();
    Expression serializer;
    Class<?> clz = getRawType(typeToken);
    if (isFinal(clz)) {
      serializer = getOrCreateSerializer(clz);
    } else {
      ListExpression writeClassAction = new ListExpression();
      Tuple2<Reference, Boolean> classInfoRef = addClassInfoField(clz);
      Expression classInfo = classInfoRef.f0;
      Expression clsExpr = new Invoke(map, "getClass", "cls", CLASS_TYPE);
      writeClassAction.add(
          new If(
              neq(new Invoke(classInfo, "getCls", CLASS_TYPE), clsExpr),
              new Assign(
                  classInfo,
                  inlineInvoke(classResolverRef, "getClassInfo", classInfoTypeToken, clsExpr))));
      // Note: writeClassExpr is thread safe.
      writeClassAction.add(
          fury.getClassResolver().writeClassExpr(classResolverRef, buffer, classInfo));
      serializer = new Invoke(classInfo, "getSerializer", "serializer", SERIALIZER_TYPE, false);
      serializer = new Cast(serializer, TypeToken.of(MapSerializer.class));
      writeClassAction.add(serializer, new Return(serializer));
      // Spit this into a separate method to avoid method too big to inline.
      serializer =
          ExpressionOptimizer.invokeGenerated(
              ctx, ImmutableSet.of(buffer, map), writeClassAction, "writeMapClassInfo", false);
    }
    Invoke size = new Invoke(map, "size", PRIMITIVE_INT_TYPE);
    Invoke writeSize = new Invoke(buffer, "writePositiveVarInt", size);
    Invoke writeHeader = new Invoke(serializer, "writeHeader", buffer, map);
    Invoke entrySet = new Invoke(map, "entrySet", "entrySet", SET_TYPE);
    ExpressionVisitor.ExprHolder exprHolder = ExpressionVisitor.ExprHolder.of("buffer", buffer);
    Expression.ForEach writeKeyValues =
        new Expression.ForEach(
            entrySet,
            (i, entryObj) -> {
              Expression entry = new Cast(entryObj, TypeToken.of(Map.Entry.class), "entry");
              Expression key = new Invoke(entry, "getKey", "keyObj", OBJECT_TYPE);
              key = tryCastIfPublic(key, keyType, "key");
              Expression value = new Invoke(entry, "getValue", "valueObj", OBJECT_TYPE);
              value = tryCastIfPublic(value, valueType, "value");
              boolean genMethodForKey =
                  useCollectionSerialization(keyType) || useMapSerialization(keyType);
              boolean genMethodForValue =
                  useCollectionSerialization(valueType) || useMapSerialization(valueType);
              return new ListExpression(
                  serializeFor(key, exprHolder.get("buffer"), keyType, genMethodForKey),
                  serializeFor(value, exprHolder.get("buffer"), valueType, genMethodForValue));
            });
    Expression hookWrite = new ListExpression(writeSize, writeHeader, writeKeyValues);
    Expression write =
        new If(
            inlineInvoke(serializer, "supportCodegenHook", PRIMITIVE_BOOLEAN_TYPE),
            hookWrite,
            new Invoke(serializer, "write", buffer, map));
    actions.add(write);
    if (generateNewMethod) {
      return ExpressionOptimizer.invokeGenerated(
          ctx, ImmutableSet.of(buffer, map), actions, "writeMap", false);
    }
    return actions;
  }

  protected Expression readRefOrNull(Expression buffer) {
    return new Invoke(refResolverRef, "readRefOrNull", "tag", PRIMITIVE_BYTE_TYPE, false, buffer);
  }

  protected Expression tryPreserveRefId(Expression buffer) {
    return new Invoke(
        refResolverRef, "tryPreserveRefId", "refId", PRIMITIVE_INT_TYPE, false, buffer);
  }

  protected Expression deserializeFor(
      Expression buffer, TypeToken<?> typeToken, Function<Expression, Expression> callback) {
    return deserializeFor(buffer, typeToken, callback, false);
  }

  /**
   * Returns an expression that deserialize a nullable <code>inputObject</code> from <code>buffer
   * </code>.
   *
   * @param callback is used to consume the deserialized value to avoid an extra condition branch.
   */
  protected Expression deserializeFor(
      Expression buffer,
      TypeToken<?> typeToken,
      Function<Expression, Expression> callback,
      boolean generateNewMethod) {
    Class<?> rawType = getRawType(typeToken);
    if (visitFury(f -> f.getClassResolver().needToWriteRef(rawType))) {
      Expression refId = tryPreserveRefId(buffer);
      // indicates that the object is first read.
      Expression needDeserialize =
          ExpressionUtils.egt(
              refId, new Expression.Literal(Fury.NOT_NULL_VALUE_FLAG, PRIMITIVE_BYTE_TYPE));
      Expression deserializedValue = deserializeForNotNull(buffer, typeToken, generateNewMethod);
      Expression setReadObject =
          new Invoke(refResolverRef, "setReadObject", refId, deserializedValue);
      Expression readValue = inlineInvoke(refResolverRef, "getReadObject", OBJECT_TYPE, false);
      // use false to ignore null
      return new If(
          needDeserialize,
          callback.apply(
              new ListExpression(refId, deserializedValue, setReadObject, deserializedValue)),
          callback.apply(readValue),
          false);
    } else {
      if (typeToken.isPrimitive()) {
        Expression value = deserializeForNotNull(buffer, typeToken, generateNewMethod);
        // Should put value expr ahead to avoid generated code in wrong scope.
        return new ListExpression(value, callback.apply(value));
      }
      Expression notNull =
          neq(
              inlineInvoke(buffer, "readByte", PRIMITIVE_BYTE_TYPE),
              new Expression.Literal(Fury.NULL_FLAG, PRIMITIVE_BYTE_TYPE));
      Expression value = deserializeForNotNull(buffer, typeToken, generateNewMethod);
      // use false to ignore null.
      return new If(
          notNull,
          callback.apply(value),
          callback.apply(ExpressionUtils.nullValue(typeToken)),
          false);
    }
  }

  /**
   * Return an expression that deserialize an not null <code>inputObject</code> from <code>buffer
   * </code>.
   */
  protected Expression deserializeForNotNull(
      Expression buffer, TypeToken<?> typeToken, boolean generateNewMethod) {
    Class<?> cls = getRawType(typeToken);
    if (isPrimitive(cls) || isBoxed(cls)) {
      // for primitive, inline call here to avoid java boxing, rather call corresponding serializer.
      if (cls == byte.class || cls == Byte.class) {
        return new Invoke(buffer, "readByte", PRIMITIVE_BYTE_TYPE);
      } else if (cls == boolean.class || cls == Boolean.class) {
        return new Invoke(buffer, "readBoolean", PRIMITIVE_BOOLEAN_TYPE);
      } else if (cls == char.class || cls == Character.class) {
        return new Invoke(buffer, "readChar", TypeToken.of(char.class));
      } else if (cls == short.class || cls == Short.class) {
        return new Invoke(buffer, "readShort", PRIMITIVE_SHORT_TYPE);
      } else if (cls == int.class || cls == Integer.class) {
        String func = fury.compressNumber() ? "readVarInt" : "readInt";
        return new Invoke(buffer, func, PRIMITIVE_INT_TYPE);
      } else if (cls == long.class || cls == Long.class) {
        String func = fury.compressNumber() ? "readVarLong" : "readLong";
        return new Invoke(buffer, func, PRIMITIVE_LONG_TYPE);
      } else if (cls == float.class || cls == Float.class) {
        return new Invoke(buffer, "readFloat", PRIMITIVE_FLOAT_TYPE);
      } else if (cls == double.class || cls == Double.class) {
        return new Invoke(buffer, "readDouble", PRIMITIVE_DOUBLE_TYPE);
      } else {
        throw new IllegalStateException("impossible");
      }
    } else {
      if (cls == String.class) {
        return fury.getStringSerializer().readStringExpr(stringSerializerRef, buffer);
      }
      Expression obj;
      if (useCollectionSerialization(typeToken)) {
        obj = deserializeForCollection(buffer, typeToken, generateNewMethod);
      } else if (useMapSerialization(typeToken)) {
        obj = deserializeForMap(buffer, typeToken, generateNewMethod);
      } else {
        if (isFinal(cls)) {
          Expression serializer = getOrCreateSerializer(cls);
          Class<?> returnType =
              ReflectionUtils.getReturnType(getRawType(serializer.type()), "read");
          obj = new Invoke(serializer, "read", TypeToken.of(returnType), buffer);
        } else {
          obj = readForNotNullNonFinal(buffer, typeToken);
        }
      }
      return obj;
    }
  }

  protected Expression readForNotNullNonFinal(Expression buffer, TypeToken<?> typeToken) {
    Expression classInfo = readClassInfo(getRawType(typeToken), buffer);
    return new Invoke(
        inlineInvoke(classInfo, "getSerializer", SERIALIZER_TYPE), "read", OBJECT_TYPE, buffer);
  }

  /**
   * Return an expression to deserialize a collection from <code>buffer</code>. Must keep consistent
   * with {@link BaseObjectCodecBuilder#serializeForCollection}
   */
  protected Expression deserializeForCollection(
      Expression buffer, TypeToken<?> typeToken, boolean generateNewMethod) {
    TypeToken<?> elementType = getElementType(typeToken);
    Expression serializer;
    Class<?> cls = getRawType(typeToken);
    if (isFinal(cls)) {
      serializer = getOrCreateSerializer(cls);
    } else {
      Expression classInfo = readClassInfo(cls, buffer);
      serializer = new Invoke(classInfo, "getSerializer", "serializer", SERIALIZER_TYPE, false);
      serializer =
          new Cast(serializer, TypeToken.of(CollectionSerializer.class), "collectionSerializer");
    }
    Invoke supportHook = inlineInvoke(serializer, "supportCodegenHook", PRIMITIVE_BOOLEAN_TYPE);
    Expression size = new Invoke(buffer, "readPositiveVarInt", "size", PRIMITIVE_INT_TYPE);
    Expression collection = new Invoke(serializer, "newCollection", COLLECTION_TYPE, buffer, size);
    // if add branch by `ArrayList`, generated code will be > 325 bytes.
    // and List#add is more likely be inlined if there is only one subclass.
    Expression hookRead = readCollectionCodegen(buffer, collection, size, elementType);
    Expression action =
        new If(
            supportHook,
            new ListExpression(collection, hookRead),
            new Invoke(serializer, "read", COLLECTION_TYPE, buffer),
            false);
    if (generateNewMethod) {
      return ExpressionOptimizer.invokeGenerated(
          ctx,
          ImmutableSet.of(buffer),
          new ListExpression(action, new Return(action)),
          "readCollection",
          false);
    }
    return action;
  }

  protected Expression readCollectionCodegen(
      Expression buffer, Expression collection, Expression size, TypeToken<?> elementType) {
    Expression start = new Expression.Literal(0, PRIMITIVE_INT_TYPE);
    Expression step = new Expression.Literal(1, PRIMITIVE_INT_TYPE);
    ExpressionVisitor.ExprHolder exprHolder =
        ExpressionVisitor.ExprHolder.of("collection", collection, "buffer", buffer);
    Expression.ForLoop readElements =
        new Expression.ForLoop(
            start,
            size,
            step,
            i -> {
              boolean generateNewMethod =
                  useCollectionSerialization(elementType) || useMapSerialization(elementType);
              return deserializeFor(
                  exprHolder.get("buffer"),
                  elementType,
                  v -> new Invoke(exprHolder.get("collection"), "add", v),
                  generateNewMethod);
            });
    // place newCollection as last as expr value
    return new ListExpression(size, collection, readElements, collection);
  }

  /**
   * Return an expression to deserialize a map from <code>buffer</code>. Must keep consistent with
   * {@link BaseObjectCodecBuilder#serializeForMap}
   */
  protected Expression deserializeForMap(
      Expression buffer, TypeToken<?> typeToken, boolean generateNewMethod) {
    Tuple2<TypeToken<?>, TypeToken<?>> keyValueType = TypeUtils.getMapKeyValueType(typeToken);
    TypeToken<?> keyType = keyValueType.f0;
    TypeToken<?> valueType = keyValueType.f1;

    Expression serializer;
    Class<?> cls = getRawType(typeToken);
    if (isFinal(cls)) {
      serializer = getOrCreateSerializer(cls);
    } else {
      Expression classInfo = readClassInfo(cls, buffer);
      serializer = new Invoke(classInfo, "getSerializer", SERIALIZER_TYPE);
      serializer = new Cast(serializer, TypeToken.of(MapSerializer.class), "mapSerializer");
    }
    Invoke supportHook = inlineInvoke(serializer, "supportCodegenHook", PRIMITIVE_BOOLEAN_TYPE);
    Expression size = new Invoke(buffer, "readPositiveVarInt", "size", PRIMITIVE_INT_TYPE);
    Expression newMap = new Invoke(serializer, "newMap", MAP_TYPE, buffer, size);
    Expression start = new Expression.Literal(0, PRIMITIVE_INT_TYPE);
    Expression step = new Expression.Literal(1, PRIMITIVE_INT_TYPE);
    ExpressionVisitor.ExprHolder exprHolder =
        ExpressionVisitor.ExprHolder.of("map", newMap, "buffer", buffer);
    Expression.ForLoop readKeyValues =
        new Expression.ForLoop(
            start,
            size,
            step,
            i -> {
              boolean genKeyMethod =
                  useCollectionSerialization(keyType) || useMapSerialization(keyType);
              boolean genValueMethod =
                  useCollectionSerialization(valueType) || useMapSerialization(valueType);
              return new Invoke(
                  exprHolder.get("map"),
                  "put",
                  deserializeFor(exprHolder.get("buffer"), keyType, e -> e, genKeyMethod),
                  deserializeFor(exprHolder.get("buffer"), valueType, e -> e, genValueMethod));
            });
    // first newMap to create map, last newMap as expr value
    Expression hookRead = new ListExpression(size, newMap, readKeyValues, newMap);
    Expression action =
        new If(supportHook, hookRead, new Invoke(serializer, "read", MAP_TYPE, buffer), false);
    if (generateNewMethod) {
      return ExpressionOptimizer.invokeGenerated(
          ctx,
          ImmutableSet.of(buffer),
          new ListExpression(action, new Return(action)),
          "readMap",
          false);
    }
    return action;
  }

  @Override
  protected Expression beanClassExpr() {
    // Serializer has a `type` field.
    return new Reference("super.type", CLASS_TYPE);
  }
}
