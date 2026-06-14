#version 450

layout(location = 0) in vec3 fragColor;
layout(location = 1) in float fragDepth;

layout(location = 0) out vec4 outColor;

void main() {
    // Simple solid color output
    // Alpha = 1.0 for solid LoD geometry
    outColor = vec4(fragColor, 1.0);
}
