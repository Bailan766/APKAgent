#!/usr/bin/env python3
"""
APKAgent 内置脚本：批量常见 Patch
用法：python3 batch_patch_common.py <workspace_dir> [patch_type]
patch_type: all, root, debug, ssl, signature, emulator
"""
import sys, os, re

PATCH_PATTERNS = {
    "root": {
        "patterns": [
            r'const-string\s+v\d+,\s*"su"',
            r'const-string\s+v\d+,\s*"/system/bin/su"',
            r'const-string\s+v\d+,\s*"/system/xbin/su"',
            r'const-string\s+v\d+,\s*"com\.noshufou\.android\.su"',
            r'const-string\s+v\d+,\s*"isRooted"',
            r'const-string\s+v\d+,\s*"checkRoot"',
        ],
        "fix": "const/4 v0, 0x0",
        "desc": "Root 检测绕过"
    },
    "debug": {
        "patterns": [
            r'invoke-static\s+\{.*\},\s*Landroid/os/Debug;->isDebuggerConnected\(\)Z',
            r'const-string\s+v\d+,\s*"TracerPid"',
        ],
        "fix": "nop",
        "desc": "调试检测绕过"
    },
    "ssl": {
        "patterns": [
            r'const-string\s+v\d+,\s*"X\.509"',
            r'invoke-virtual\s+\{.*\},\s*Ljavax/net/ssl/TrustManager;',
        ],
        "fix": "nop",
        "desc": "SSL Pinning 绕过"
    },
    "signature": {
        "patterns": [
            r'GET_SIGNATURES',
            r'getPackageInfo.*SIGNATURES',
            r'checkSignature',
        ],
        "fix": "nop",
        "desc": "签名校验绕过"
    },
    "emulator": {
        "patterns": [
            r'const-string\s+v\d+,\s*"generic"',
            r'const-string\s+v\d+,\s*"sdk_gphone"',
            r'const-string\s+v\d+,\s*"emulator"',
            r'const-string\s+v\d+,\s*"isEmulator"',
        ],
        "fix": "const/4 v0, 0x0",
        "desc": "模拟器检测绕过"
    }
}

def batch_patch(workspace, patch_type="all"):
    results = []
    smali_dir = os.path.join(workspace, "smali")
    if not os.path.isdir(smali_dir):
        # 尝试多级 smali 目录
        for d in os.listdir(workspace):
            if d.startswith("smali"):
                smali_dir = os.path.join(workspace, d)
                break
    
    if not os.path.isdir(smali_dir):
        print(f"错误: 未找到 smali 目录 ({workspace})")
        return

    patches_to_apply = PATCH_PATTERNS if patch_type == "all" else {patch_type: PATCH_PATTERNS[patch_type]}
    
    for root, dirs, files in os.walk(smali_dir):
        for fname in files:
            if not fname.endswith('.smali'):
                continue
            fpath = os.path.join(root, fname)
            with open(fpath, 'r', encoding='utf-8', errors='ignore') as f:
                content = f.read()
            
            modified = False
            for ptype, patch_info in patches_to_apply.items():
                for pattern in patch_info["patterns"]:
                    if re.search(pattern, content):
                        # 找到匹配，修改附近的条件跳转
                        lines = content.split('\n')
                        for i, line in enumerate(lines):
                            if re.search(pattern, line):
                                # 查找附近的 if 跳转
                                for j in range(max(0, i-3), min(len(lines), i+3)):
                                    if 'if-eqz' in lines[j]:
                                        lines[j] = lines[j].replace('if-eqz', 'if-nez')
                                        modified = True
                                    elif 'if-nez' in lines[j]:
                                        lines[j] = lines[j].replace('if-nez', 'if-eqz')
                                        modified = True
                        if modified:
                            results.append(f"  {patch_info['desc']}: {fname}")
            
            if modified:
                with open(fpath, 'w', encoding='utf-8') as f:
                    f.write('\n'.join(lines))
    
    print(f"批量 Patch 完成：修改了 {len(results)} 处")
    for r in results:
        print(r)

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("用法: python3 batch_patch_common.py <workspace_dir> [patch_type]")
        sys.exit(1)
    workspace = sys.argv[1]
    patch_type = sys.argv[2] if len(sys.argv) > 2 else "all"
    batch_patch(workspace, patch_type)
