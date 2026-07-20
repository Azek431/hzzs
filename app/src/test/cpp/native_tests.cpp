#include "../../main/cpp/color_components.h"
#include "../../main/cpp/vision_engine.h"
#include <cassert>
#include <cstdint>
#include <iostream>
#include <vector>

int main() {
    using namespace hzzs;
    std::vector<uint32_t> pixels(32 * 16, 0xff000000u);
    for (int y=2;y<6;++y) for(int x=2;x<6;++x) pixels[y*32+x]=0xffffffffu;
    for (int y=8;y<13;++y) for(int x=22;x<28;++x) pixels[y*32+x]=0xffffffffu;
    FrameView frame{pixels.data(),32,16};
    const auto parts=components(frame,[](int r,int g,int b,int,int){return r>240&&g>240&&b>240;},1,2);
    assert(parts.size()==2); // ABI and algorithms may retain same-kind objects.
    assert(!analyze(0,{nullptr,0,0},320,0xFF,true,0.185f).error.empty());
    assert(!analyze(0,frame,100,0xFF,true,0.185f).error.empty());
    const auto blank=analyze(0,frame,320,0xFF,true,0.185f);
    for(const auto& d:blank.detections) assert(!d.actionable || d.kind!=Kind::PLAYER);
    std::cout << "native unit tests PASS\n";
}
