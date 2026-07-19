#pragma once
#include "vision_types.h"
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <functional>
#include <queue>
#include <vector>

namespace hzzs {
struct Component { int left, top, right, bottom, pixels; };
inline int red(uint32_t p) { return static_cast<int>((p >> 16) & 0xff); }
inline int green(uint32_t p) { return static_cast<int>((p >> 8) & 0xff); }
inline int blue(uint32_t p) { return static_cast<int>(p & 0xff); }
inline std::vector<Component> components(
    const FrameView& frame,
    const std::function<bool(int,int,int,int,int)>& predicate,
    int stride,
    int min_pixels
) {
    const int sw = (frame.width + stride - 1) / stride;
    const int sh = (frame.height + stride - 1) / stride;
    std::vector<uint8_t> mask(static_cast<size_t>(sw) * sh);
    std::vector<uint8_t> seen(mask.size());
    for (int y = 0; y < sh; ++y) for (int x = 0; x < sw; ++x) {
        const int px = std::min(x * stride, frame.width - 1);
        const int py = std::min(y * stride, frame.height - 1);
        const auto c = frame.pixels[static_cast<size_t>(py) * frame.width + px];
        mask[static_cast<size_t>(y) * sw + x] = predicate(red(c), green(c), blue(c), px, py) ? 1 : 0;
    }
    std::vector<Component> out;
    std::queue<std::pair<int,int>> q;
    for (int sy=0; sy<sh; ++sy) for (int sx=0; sx<sw; ++sx) {
        const size_t si=static_cast<size_t>(sy)*sw+sx;
        if (!mask[si] || seen[si]) continue;
        seen[si]=1; q.push({sx,sy});
        int l=sx,t=sy,r=sx,b=sy,count=0;
        while(!q.empty()) {
            auto [x,y]=q.front(); q.pop(); ++count;
            l=std::min(l,x); r=std::max(r,x); t=std::min(t,y); b=std::max(b,y);
            constexpr int dx[4]={-1,1,0,0}; constexpr int dy[4]={0,0,-1,1};
            for(int k=0;k<4;++k){int nx=x+dx[k],ny=y+dy[k]; if(nx<0||ny<0||nx>=sw||ny>=sh)continue;
                size_t ni=static_cast<size_t>(ny)*sw+nx; if(mask[ni]&&!seen[ni]){seen[ni]=1;q.push({nx,ny});}}
        }
        if (count>=min_pixels) out.push_back({l*stride,t*stride,std::min(frame.width,(r+1)*stride),std::min(frame.height,(b+1)*stride),count});
    }
    return out;
}
inline Rect norm(const Component& c, const FrameView& f) {
    return {c.left/static_cast<float>(f.width), c.top/static_cast<float>(f.height), c.right/static_cast<float>(f.width), c.bottom/static_cast<float>(f.height)};
}
}
