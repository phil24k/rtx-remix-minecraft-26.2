/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.architecture;

import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

class MixinTargetsTest {
    static final Map<String, ClassNode> cache = new HashMap<>();
    static final List<String> failures = new ArrayList<>();
    static ClassNode read(String name) throws Exception {
        if (cache.containsKey(name)) return cache.get(name);
        var stream = MixinTargetsTest.class.getClassLoader().getResourceAsStream(name + ".class");
        if (stream == null) return null;
        var node = new ClassNode();
        try (stream) { new ClassReader(stream).accept(node, 0); }
        cache.put(name, node);
        return node;
    }
    static List<AnnotationNode> annotations(List<AnnotationNode> a, List<AnnotationNode> b) {
        var all = new ArrayList<AnnotationNode>();
        if (a != null) all.addAll(a);
        if (b != null) all.addAll(b);
        return all;
    }
    static Object value(AnnotationNode a, String name) {
        if (a.values == null) return null;
        for (int i = 0; i < a.values.size(); i += 2)
            if (a.values.get(i).equals(name)) return a.values.get(i + 1);
        return null;
    }
    static List<MethodNode> methods(ClassNode c, String selector) throws Exception {
        int index = selector.indexOf('(');
        String name = index < 0 ? selector : selector.substring(0, index);
        String desc = index < 0 ? null : selector.substring(index);
        var found = new ArrayList<MethodNode>();
        for (var m : c.methods)
            if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) found.add(m);
        return found;
    }
    static boolean field(ClassNode c, String name, String desc) throws Exception {
        if (c == null) return false;
        for (var f : c.fields) if (f.name.equals(name) && (desc == null || f.desc.equals(desc))) return true;
        return field(read(c.superName), name, desc);
    }
    static void check(ClassNode mixin, ClassNode target) throws Exception {
        String label = mixin.name.substring(mixin.name.lastIndexOf('/') + 1);
        for (var f : mixin.fields) {
            for (var a : annotations(f.visibleAnnotations, f.invisibleAnnotations))
                if (a.desc.endsWith("/Shadow;") && !field(target, f.name, f.desc))
                    failures.add(label + ": missing shadow field " + f.name + " " + f.desc);
        }
        for (var m : mixin.methods) {
            for (var a : annotations(m.visibleAnnotations, m.invisibleAnnotations)) {
                if (a.desc.endsWith("/Accessor;")) {
                    String name = (String)value(a, "value");
                    if (name == null) name = Character.toLowerCase(m.name.charAt(3)) + m.name.substring(4);
                    String desc = Type.getReturnType(m.desc).getDescriptor();
                    if (desc.equals("V")) desc = Type.getArgumentTypes(m.desc)[0].getDescriptor();
                    if (!field(target, name, desc)) failures.add(label + ": missing accessor field " + name + " " + desc);
                }
                if (a.desc.endsWith("/Shadow;") && methods(target, m.name + m.desc).isEmpty())
                    failures.add(label + ": missing shadow method " + m.name + m.desc);
                Object selectorValue = value(a, "method");
                if (selectorValue == null) continue;
                var selectors = selectorValue instanceof List<?> list ? list : List.of(selectorValue);
                for (var s : selectors) {
                    var targets = methods(target, (String)s);
                    if (targets.isEmpty()) {
                        failures.add(label + "." + m.name + ": missing target method " + s);
                        continue;
                    }
                    Object ats = value(a, "at");
                    if (ats == null) continue;
                    var points = ats instanceof List<?> list ? list : List.of(ats);
                    for (var point : points) {
                        var at = (AnnotationNode)point;
                        String type = (String)value(at, "value");
                        String member = (String)value(at, "target");
                        if (member == null || !(type.equals("INVOKE") || type.equals("FIELD"))) continue;
                        boolean found = false;
                        for (var tm : targets) for (var ins : tm.instructions) {
                            if (ins instanceof MethodInsnNode call)
                                found |= member.equals("L" + call.owner + ";" + call.name + call.desc);
                            if (ins instanceof FieldInsnNode access)
                                found |= member.equals("L" + access.owner + ";" + access.name + ":" + access.desc);
                        }
                        if (!found) failures.add(label + "." + m.name + ": missing " + type + " in " + s + " -> " + member);
                    }
                }
            }
        }
    }
    @org.junit.jupiter.api.Test
    void allConfiguredTargetsMatchMinecraft262() throws Exception {
        failures.clear();
        cache.clear();
        var loader = getClass().getClassLoader();
        com.google.gson.JsonObject config;
        try (var stream = loader.getResourceAsStream("rtx-remix-renderer.mixins.json")) {
            config = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(stream,
                    java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
        String prefix = config.get("package").getAsString().replace('.', '/') + "/";
        int checked = 0;
        for (String section : List.of("mixins", "client")) {
            for (var entry : config.getAsJsonArray(section)) {
                var mixin = read(prefix + entry.getAsString());
                for (var annotation : annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations)) {
                    if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) continue;
                    var names = new ArrayList<String>();
                    Object types = value(annotation, "value");
                    if (types instanceof List<?> list) for (var type : list) names.add(((Type) type).getInternalName());
                    Object strings = value(annotation, "targets");
                    if (strings instanceof List<?> list) for (var name : list) names.add(((String) name).replace('.', '/'));
                    for (String name : names) {
                        var target = read(name);
                        if (target == null) failures.add(name + ": missing mixin target");
                        else {
                            check(mixin, target);
                            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                                    () -> Class.forName(name.replace('/', '.'), false, loader), name);
                            checked++;
                        }
                    }
                }
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(checked > 0, "No targets were checked");
        org.junit.jupiter.api.Assertions.assertEquals(List.of(), failures, String.join("\n", failures));
    }
}
