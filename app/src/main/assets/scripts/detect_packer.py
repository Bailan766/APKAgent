#!/usr/bin/env python3
"""
APKAgent 内置脚本：加壳检测
用法：python3 detect_packer.py <apk_path>
"""
import sys, json, zipfile, os

PACKER_SIGNATURES = {
    "360加固": ["libprotectClass.so", "libjiagu.so", "libjiagu_x86.so"],
    "腾讯乐固": ["libshell-super.so", "libshella-*.so", "libtxAppProtect.so"],
    "梆梆安全": ["libDexHelper.so", "libSecShell.so", "libDexHelper-x86.so"],
    "爱加密": ["libexec.so", "libexecmain.so", "libijiami.so"],
    "网易易盾": ["libnesec.so", "libnesl.so"],
    "通付盾": ["libtfd.so"],
    "娜迦信息": ["libchaosvmp.so"],
    "几维安全": ["libkwscmm.so", "libkwssl.so"],
    "APKProtect": ["libAPKProtect.so"],
    "DexProtector": ["libdexprotector.so"],
    "Bangcle": ["libsecexe.so", "libSecShell.so"],
}

def detect_packer(apk_path):
    result = {"apk": apk_path, "packers": [], "dex_info": {}, "protection_score": 0}
    
    try:
        with zipfile.ZipFile(apk_path, 'r') as zf:
            names = zf.namelist()
            
            # 1. 检测壳特征 SO 文件
            for packer, signatures in PACKER_SIGNATURES.items():
                for sig in signatures:
                    if '*' in sig:
                        import fnmatch
                        if any(fnmatch.fnmatch(n, sig) for n in names):
                            result["packers"].append(packer)
                            break
                    elif sig in names:
                        result["packers"].append(packer)
                        break
            
            # 2. 分析 DEX 结构
            dex_files = [n for n in names if n.endswith('.dex')]
            result["dex_info"]["count"] = len(dex_files)
            result["dex_info"]["files"] = dex_files
            
            for dex in dex_files:
                info = zf.getinfo(dex)
                result["dex_info"][dex] = {
                    "size": info.file_size,
                    "compressed": info.compress_size,
                }
            
            # 3. 检测其他保护特征
            has_meta_inf = any(n.startswith("META-INF/") for n in names)
            has_lib = any(n.startswith("lib/") for n in names)
            assets_count = len([n for n in names if n.startswith("assets/")])
            
            # 4. 判断加壳可能性
            score = 0
            if result["packers"]:
                score += 60
            if len(dex_files) > 1:
                score += 20
            # 检查 DEX 是否被加密（文件大小异常小）
            for dex in dex_files:
                info = zf.getinfo(dex)
                if info.file_size < 1000:  # 正常 DEX 至少几 KB
                    score += 30
            if has_lib and any(n.endswith('.so') for n in names):
                native_libs = [n for n in names if n.startswith("lib/") and n.endswith('.so')]
                if len(native_libs) > 5:
                    score += 10
            
            result["protection_score"] = min(100, score)
            result["is_packed"] = score > 50
            
    except Exception as e:
        result["error"] = str(e)
    
    print(json.dumps(result, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("用法: python3 detect_packer.py <apk_path>")
        sys.exit(1)
    detect_packer(sys.argv[1])
