#version 450

// Vertex input: position (xyz) + color (rgb)
layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec3 inColor;

// Output to fragment shader
layout(location = 0) out vec3 fragColor;
layout(location = 1) out float fragDepth;

// Push constants: view-projection matrix (64 bytes)
layout(push_constant) uniform PushConstants {
    mat4 viewProj;
} push;

void main() {
    vec4 clipPos = push.viewProj * vec4(inPosition, 1.0);
    gl_Position = clipPos;

    // Simple distance-based shading so far LoD looks slightly darker
    float dist = length(clipPos.xyz);
    float shade = clamp(1.0 - dist * 0.0003, 0.5, 1.0);

    fragColor = inColor * shade;
    fragDepth = clipPos.z / clipPos.w;
}
