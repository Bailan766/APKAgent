#!/usr/bin/env python3
"""
APKAgent 内置脚本：根据方法签名生成 Frida Hook
用法：python3 generate_frida_hook.py <class_name> <method_name> [return_value]
"""
import sys

def generate_hook(class_name, method_name, return_value="false"):
    dot_class = class_name.replace("/", ".")
    
    script = f"""
// Auto-generated Frida Hook by APKAgent
// Target: {dot_class}.{method_name}

Java.perform(function() {{
    var clazz = Java.use("{dot_class}");
    
    // Hook all overloads
    var overloads = clazz.{method_name}.overloads;
    console.log("[*] Found " + overloads.length + " overloads of {method_name}");
    
    overloads.forEach(function(overload) {{
        overload.implementation = function() {{
            console.log("[+] {dot_class}.{method_name} called");
            
            // Log arguments
            for (var i = 0; i < arguments.length; i++) {{
                console.log("    arg[" + i + "]: " + arguments[i]);
            }}
            
            // Log call stack
            console.log("    Stack:\n" + 
                Java.use("android.util.Log").getStackTraceString(
                    Java.use("java.lang.Exception").$new()
                ).split("\n").slice(1, 8).join("\n    "));
            
            // Call original and log result
            var result = this.{method_name}.apply(this, arguments);
            console.log("    Original result: " + result);
            
            // Return forced value
            return {return_value};
        }};
    }});
    
    console.log("[+] Hook installed: {dot_class}.{method_name} -> return {return_value}");
}});
"""
    return script

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("用法: python3 generate_frida_hook.py <class_name> <method_name> [return_value]")
        sys.exit(1)
    
    cls = sys.argv[1]
    method = sys.argv[2]
    ret = sys.argv[3] if len(sys.argv) > 3 else "false"
    
    print(generate_hook(cls, method, ret))
