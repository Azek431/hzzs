#include "vision_engine.h"
#include "color_components.h"
#include "scene_geometry.h"
#include <algorithm>
#include <cmath>

namespace hzzs {
Result analyze_bamboo(const FrameView& f, int work_width, int enabled_kind_mask, bool detect_player, float fixed_player_x_ratio) {
    Result out;
    const auto ground=estimate_bamboo_ground(f);
    const bool blocked=has_blocking_overlay(f);
    if(ground.confidence<.28f){out.scene_confidence=ground.confidence*.35f;return out;}
    Component player_component{};
    bool player_found=false;
    if(detect_player){
        const auto players=player_candidates(f,work_width,ground.y,false);
        player_found=choose_player(f,players,ground.y,&player_component);
        if(player_found) out.detections.push_back({1,Kind::PLAYER,norm(player_component,f),.88f,false,false,Avoidance::NONE});
    }
    const int player_right=player_found?player_component.right:static_cast<int>(f.width*fixed_player_x_ratio);
    out.scene_confidence=detect_player
        ? std::clamp(.42f*ground.confidence+(player_found?.58f:.08f),0.0f,1.0f)
        : std::clamp(.88f*ground.confidence+.12f,0.0f,1.0f);
    if(blocked) out.scene_confidence=std::min(out.scene_confidence,.45f);
    if((detect_player&&!player_found)||blocked) return out;

    const int x_stride=adaptive_stride(f,work_width); int hint=200;

    // Hanging brushes are detected first. Their dark handle and white body can
    // otherwise look like a gray statue when the scene is sampled coarsely.
    std::vector<Rect> brush_regions;
    if(kind_enabled(enabled_kind_mask,Kind::HANGING_BRUSH)){
    auto brushes=column_regions(f,[](int r,int g,int b){return r<94&&g<84&&b<74;},
        static_cast<int>(f.height*.18f),ground.y+static_cast<int>(f.height*.04f),x_stride,
        std::max(4,static_cast<int>(f.height*.04f)/x_stride),static_cast<int>(f.width*.032f),static_cast<int>(f.width*.025f));
    for(const auto& c:brushes){
        const float w=normalized_width(c,f),h=normalized_height(c,f),aspect=h/std::max(w,.001f);
        if(w<.032f||w>.23f||h<.10f||h>.54f||aspect<1.65f||center_y(c,f)<.28f) continue;
        Rect box=norm(c,f);box.left=std::max(0.0f,box.left-.040f);box.right=std::min(1.0f,box.right+.040f);box.top=std::max(0.0f,box.top-.025f);box.bottom=std::min(1.0f,box.bottom+.10f);
        brush_regions.push_back(box);
        out.detections.push_back({hint++,Kind::HANGING_BRUSH,box,.84f,true,false,Avoidance::SLIDE});
    }
    }

    if(kind_enabled(enabled_kind_mask,Kind::PANDA_STATUE)){
    auto statue_parts=components(f,[&](int r,int g,int b,int,int y){
        const int maxc=std::max({r,g,b}),minc=std::min({r,g,b});
        return y>f.height*.34f&&y<ground.y+f.height*.06f&&maxc-minc<48&&r>32&&r<185&&g<190&&b<195;
    },x_stride,std::max(5,14/x_stride));
    auto statues=merge_components(std::move(statue_parts),static_cast<int>(f.width*.035f),static_cast<int>(f.height*.055f));
    for(const auto& c:statues){
        const float w=normalized_width(c,f),h=normalized_height(c,f),bottom_gap=std::abs(c.bottom-ground.y)/static_cast<float>(f.height);
        if(w<.05f||w>.34f||h<.075f||h>.35f||bottom_gap>.13f||c.right<=player_right) continue;
        const bool overlaps_brush=std::any_of(brush_regions.begin(),brush_regions.end(),[&](const Rect& brush){
            return intersection_over_candidate(c,brush,f)>.35f;
        });
        if(overlaps_brush) continue;
        const float neutral=region_fraction(f,c,[](int r,int g,int b){return std::max({r,g,b})-std::min({r,g,b})<52&&r<195;});
        const float dark=region_fraction(f,c,[](int r,int g,int b){return r<105&&g<105&&b<110;});
        if(neutral<.13f||dark<.025f) continue;
        out.detections.push_back({hint++,Kind::PANDA_STATUE,norm(c,f),.86f,true,false,Avoidance::JUMP});
    }
    }

    if(kind_enabled(enabled_kind_mask,Kind::BAMBOO_GAP)){
    auto gaps=column_regions(f,[](int r,int g,int b){return r>35&&r>g*1.10f&&g>b*1.05f&&r<160&&b<100;},
        ground.y-static_cast<int>(f.height*.03f),static_cast<int>(f.height*.97f),x_stride,
        std::max(6,static_cast<int>(f.height*.09f)/x_stride),static_cast<int>(f.width*.135f),static_cast<int>(f.width*.02f));
    for(const auto& c:gaps){
        const float w=normalized_width(c,f),h=normalized_height(c,f);
        if(w<.135f||w>.78f||h<.11f||c.top>ground.y+f.height*.065f) continue;
        // A true gap interrupts the green walking surface. Brown scenery below
        // a continuous bamboo platform is not an obstacle.
        const float continuous_ground=horizontal_fraction(
            f,c.left,c.right,ground.y,std::max(2,static_cast<int>(f.height*.006f)),
            [](int r,int g,int b){return bamboo_green(r,g,b);});
        if(continuous_ground>.34f) continue;
        if(c.right<=player_right) continue;
        out.detections.push_back({hint++,Kind::BAMBOO_GAP,norm(c,f),.85f,true,false,w>.22f?Avoidance::DOUBLE_JUMP:Avoidance::JUMP});
    }
    }
    return out;
}
} // namespace hzzs
