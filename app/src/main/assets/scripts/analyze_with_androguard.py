#!/usr/bin/env python3
"""
APKAgent 内置脚本：Androguard 深度分析
用法：python3 analyze_with_androguard.py <apk_path>
"""
import sys, json

def analyze_apk(apk_path):
    try:
        from androguard.core.apk import APK
        from androguard.core.dex import DEX
    except ImportError:
        print(json.dumps({"error": "androguard 未安装，请执行: pip install androguard"}))
        return

    apk = APK(apk_path)
    result = {
        "package": apk.get_package(),
        "version": apk.get_androidversion_name(),
        "min_sdk": apk.get_min_sdk_version(),
        "target_sdk": apk.get_target_sdk_version(),
        "permissions": apk.get_permissions(),
        "activities": apk.get_activities(),
        "services": apk.get_services(),
        "receivers": apk.get_receivers(),
        "providers": apk.get_providers(),
        "main_activity": apk.get_main_activity(),
    }
    
    # 分析 DEX
    dex_files = apk.get_all_dex()
    result["dex_count"] = len(dex_files)
    
    # 提取类名
    classes = set()
    for dex in dex_files:
        d = DEX(dex)
        for c in d.get_classes():
            classes.add(c.get_name())
    result["class_count"] = len(classes)
    result["sample_classes"] = sorted(list(classes))[:50]
    
    # 搜索关键字符串
    keywords = ["password", "secret", "api_key", "token", "http", "https", "encrypt", "decrypt"]
    found = {}
    for dex in dex_files:
        d = DEX(dex)
        for s in d.get_strings():
            for kw in keywords:
                if kw in s.lower():
                    found.setdefault(kw, []).append(s[:100])
    result["keyword_matches"] = {k: v[:10] for k, v in found.items()}
    
    print(json.dumps(result, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("用法: python3 analyze_with_androguard.py <apk_path>")
        sys.exit(1)
    analyze_apk(sys.argv[1])
