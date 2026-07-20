#pragma once
#include "vision_types.h"
#include "color_components.h"
#include <algorithm>
#include <cmath>
#include <vector>

namespace hzzs {
inline int adaptive_stride(const FrameView& f, int work_width, int multiplier = 1) {
    return std::max(1, static_cast<int>(std::ceil(f.width / static_cast<float>(std::max(1, work_width)))) * multiplier);
}
inline bool sweet_white(int r,int g,int b) { return r>190 && g>155 && b>135 && std::abs(r-g)<85; }
inline bool sweet_pink(int r,int g,int b) { return r>175 && b>100 && r>g*1.08f && r-b<115; }
inline bool bamboo_green(int r,int g,int b) { return g>80 && g>=r*0.78f && g>b*1.25f && b<125; }

struct GroundEstimate { int y{}; float confidence{}; };
inline GroundEstimate estimate_sweet_ground(const FrameView& f) {
    GroundEstimate best{static_cast<int>(f.height*0.69f),0.0f};
    const int step=std::max(2,f.width/240);
    for(int y=static_cast<int>(f.height*.50f);y<static_cast<int>(f.height*.82f);y+=std::max(1,f.height/320)){
        int white=0,pink=0,total=0; const int below=std::min(f.height-1,y+std::max(3,f.height/70));
        for(int x=0;x<f.width;x+=step){
            auto a=f.pixels[static_cast<size_t>(y)*f.width+x], c=f.pixels[static_cast<size_t>(below)*f.width+x];
            white += sweet_white(red(a),green(a),blue(a)); pink += sweet_pink(red(c),green(c),blue(c)); ++total;
        }
        const float score=.52f*white/std::max(1,total)+.48f*pink/std::max(1,total);
        if(score>best.confidence) best={y,std::min(1.0f,score*1.35f)};
    }
    return best;
}
inline GroundEstimate estimate_bamboo_ground(const FrameView& f) {
    GroundEstimate best{static_cast<int>(f.height*.68f),0.0f}; const int step=std::max(2,f.width/240);
    for(int y=static_cast<int>(f.height*.52f);y<static_cast<int>(f.height*.82f);y+=std::max(1,f.height/320)){
        int green_count=0,total=0;
        for(int x=0;x<f.width;x+=step){auto p=f.pixels[static_cast<size_t>(y)*f.width+x]; green_count+=bamboo_green(red(p),green(p),blue(p)); ++total;}
        const float score=green_count/static_cast<float>(std::max(1,total));
        if(score>best.confidence) best={y,std::min(1.0f,score*1.8f)};
    }
    return best;
}
inline float normalized_width(const Component& c,const FrameView& f){return (c.right-c.left)/static_cast<float>(f.width);}
inline float normalized_height(const Component& c,const FrameView& f){return (c.bottom-c.top)/static_cast<float>(f.height);}
inline float center_x(const Component& c,const FrameView& f){return (c.left+c.right)*.5f/f.width;}
inline float center_y(const Component& c,const FrameView& f){return (c.top+c.bottom)*.5f/f.height;}

inline float region_fraction(
    const FrameView& f,
    const Component& c,
    const std::function<bool(int,int,int)>& predicate
);

inline bool player_core_pixel(int r, int g, int b, bool sweet_scene) {
    const int maxc = std::max({r, g, b});
    const int minc = std::min({r, g, b});
    if (sweet_scene) {
        // The sweet scene itself is pink. Require a stronger red/chroma core
        // than the pastel background so the player remains a compact region.
        return r > 175 && b > 45 && r - g > 55 && maxc - minc > 65;
    }
    // The bamboo scene is warm yellow. A slightly softer red-dominance
    // threshold keeps the antialiased player body while rejecting scenery.
    return r > 180 && b > 48 && r - g > 40 && maxc - minc > 45;
}

inline std::vector<Component> merge_player_parts(
    const FrameView& f,
    std::vector<Component> input,
    int stride
) {
    std::sort(input.begin(), input.end(), [](const Component& a, const Component& b) {
        return a.pixels > b.pixels;
    });
    std::vector<Component> output;
    std::vector<bool> used(input.size(), false);
    const int x_gap = std::max(stride, static_cast<int>(f.width * .032f));
    const int y_gap = std::max(stride, static_cast<int>(f.height * .024f));
    for (size_t i = 0; i < input.size(); ++i) {
        if (used[i]) continue;
        Component merged = input[i];
        used[i] = true;
        bool changed = true;
        while (changed) {
            changed = false;
            for (size_t j = 0; j < input.size(); ++j) {
                if (used[j]) continue;
                const auto& next = input[j];
                const int combined_left = std::min(merged.left, next.left);
                const int combined_top = std::min(merged.top, next.top);
                const int combined_right = std::max(merged.right, next.right);
                const int combined_bottom = std::max(merged.bottom, next.bottom);
                const float combined_w = (combined_right - combined_left) / static_cast<float>(f.width);
                const float combined_h = (combined_bottom - combined_top) / static_cast<float>(f.height);
                if (combined_w > .31f || combined_h > .26f) continue;
                const bool x_close = next.left <= merged.right + x_gap && next.right >= merged.left - x_gap;
                const bool y_close = next.top <= merged.bottom + y_gap && next.bottom >= merged.top - y_gap;
                const float next_w = normalized_width(next, f);
                const float next_h = normalized_height(next, f);
                // Do not attach long one-row platform/effect trails to the body.
                const bool useful_shape = next_h >= .010f || next_w <= .075f;
                if (x_close && y_close && useful_shape) {
                    merged = {combined_left, combined_top, combined_right, combined_bottom, merged.pixels + next.pixels};
                    used[j] = true;
                    changed = true;
                }
            }
        }
        output.push_back(merged);
    }
    return output;
}

inline std::vector<Component> player_candidates(
    const FrameView& f,
    int work_width,
    int ground_y,
    bool sweet_scene
) {
    const int stride = adaptive_stride(f, work_width);
    auto raw = components(f, [&](int r, int g, int b, int x, int y) {
        return player_core_pixel(r, g, b, sweet_scene) &&
               y > f.height * .27f &&
               y < std::min(f.height * .87f, ground_y + f.height * .13f) &&
               x < f.width * .68f;
    }, stride, std::max(3, 8 / stride));

    raw.erase(std::remove_if(raw.begin(), raw.end(), [&](const Component& c) {
        const float w = normalized_width(c, f);
        const float h = normalized_height(c, f);
        const float aspect = h / std::max(w, .001f);
        return w < .012f || w > .32f || h < .008f || h > .27f || aspect < .06f || aspect > 6.0f;
    }), raw.end());

    auto candidates = merge_player_parts(f, std::move(raw), stride);
    candidates.erase(std::remove_if(candidates.begin(), candidates.end(), [&](const Component& c) {
        const float w = normalized_width(c, f);
        const float h = normalized_height(c, f);
        const float cy = center_y(c, f);
        const float aspect = h / std::max(w, .001f);
        const int sampled_width = std::max(1, (c.right - c.left + stride - 1) / stride);
        const int sampled_height = std::max(1, (c.bottom - c.top + stride - 1) / stride);
        const float density = c.pixels / static_cast<float>(sampled_width * sampled_height);
        return w < .032f || w > .30f || h < .020f || h > .25f ||
               cy < .275f || cy > .86f || aspect < .16f || aspect > 4.2f || density < .025f;
    }), candidates.end());
    return candidates;
}

inline bool choose_player(const FrameView& f,const std::vector<Component>& candidates,int ground_y,Component* selected){
    if(candidates.empty()) return false;
    float best=-1e9f;
    Component choice{};
    for(const auto& c:candidates){
        const float w=normalized_width(c,f),h=normalized_height(c,f),cx=center_x(c,f);
        const float expected_size=std::exp(-std::pow((w-.145f)/.125f,2)-std::pow((h-.085f)/.095f,2));
        const float ground_distance=std::abs(c.bottom-ground_y)/static_cast<float>(f.height);
        const float near_ground=std::exp(-std::pow(ground_distance/.25f,2));
        const float left_bias=std::exp(-std::pow((cx-.22f)/.31f,2));
        const float area_score=std::min(.9f,c.pixels/180.0f);
        const float score=1.70f*expected_size+1.35f*near_ground+4.45f*left_bias+area_score;
        if(score>best){best=score;choice=c;}
    }
    if(best<4.60f) return false;
    *selected=choice;
    return true;
}
inline float region_fraction(
    const FrameView& f,
    const Component& c,
    const std::function<bool(int,int,int)>& predicate
) {
    const int step=std::max(1,f.width/360); int hits=0,total=0;
    const int pad=std::max(2,(c.right-c.left)/5);
    for(int y=std::max(0,c.top-pad);y<std::min(f.height,c.bottom+pad);y+=step){
        for(int x=std::max(0,c.left-pad);x<std::min(f.width,c.right+pad);x+=step){
            const auto p=f.pixels[static_cast<size_t>(y)*f.width+x];
            hits+=predicate(red(p),green(p),blue(p));++total;
        }
    }
    return hits/static_cast<float>(std::max(1,total));
}


inline float horizontal_fraction(
    const FrameView& f,
    int x1,
    int x2,
    int y_center,
    int y_radius,
    const std::function<bool(int,int,int)>& predicate
) {
    x1=std::clamp(x1,0,f.width); x2=std::clamp(x2,0,f.width);
    const int top=std::clamp(y_center-y_radius,0,f.height);
    const int bottom=std::clamp(y_center+y_radius+1,0,f.height);
    const int step=std::max(1,f.width/480); int hits=0,total=0;
    for(int y=top;y<bottom;y+=step){
        for(int x=x1;x<x2;x+=step){
            const auto p=f.pixels[static_cast<size_t>(y)*f.width+x];
            hits+=predicate(red(p),green(p),blue(p)); ++total;
        }
    }
    return hits/static_cast<float>(std::max(1,total));
}

inline float intersection_over_candidate(const Component& candidate,const Rect& other,const FrameView& f) {
    const int ol=static_cast<int>(other.left*f.width), ot=static_cast<int>(other.top*f.height);
    const int oright=static_cast<int>(other.right*f.width), ob=static_cast<int>(other.bottom*f.height);
    const int il=std::max(candidate.left,ol), it=std::max(candidate.top,ot);
    const int ir=std::min(candidate.right,oright), ib=std::min(candidate.bottom,ob);
    if(ir<=il||ib<=it) return 0.0f;
    const float intersection=static_cast<float>((ir-il)*(ib-it));
    const float area=static_cast<float>(std::max(1,(candidate.right-candidate.left)*(candidate.bottom-candidate.top)));
    return intersection/area;
}

inline std::vector<Component> column_regions(
    const FrameView& f,
    const std::function<bool(int,int,int)>& predicate,
    int y_start,
    int y_end,
    int x_stride,
    int minimum_hits,
    int minimum_width,
    int merge_gap
) {
    struct Column {int x;int top;int bottom;bool active;};
    std::vector<Column> columns;
    for(int x=0;x<f.width;x+=std::max(1,x_stride)){
        int hits=0,top=y_end,bottom=y_start;
        for(int y=std::max(0,y_start);y<std::min(f.height,y_end);y+=std::max(1,x_stride)){
            const auto p=f.pixels[static_cast<size_t>(y)*f.width+x];
            if(predicate(red(p),green(p),blue(p))){++hits;top=std::min(top,y);bottom=std::max(bottom,y+std::max(1,x_stride));}
        }
        columns.push_back({x,top,bottom,hits>=minimum_hits});
    }
    std::vector<Component> out; int start=-1,last=-1,top=y_end,bottom=y_start,hits=0;
    auto flush=[&](){
        if(start>=0 && last-start+x_stride>=minimum_width) out.push_back({start,top,std::min(f.width,last+x_stride),bottom,hits});
        start=last=-1;top=y_end;bottom=y_start;hits=0;
    };
    for(const auto& col:columns){
        if(col.active){
            if(start<0){start=col.x;} else if(col.x-last>merge_gap){flush();start=col.x;}
            last=col.x;top=std::min(top,col.top);bottom=std::max(bottom,col.bottom);++hits;
        } else if(start>=0 && col.x-last>merge_gap){flush();}
    }
    flush(); return out;
}

inline bool has_blocking_overlay(const FrameView& f) {
    const int step=std::max(2,f.width/180); int blue_count=0,dark=0,orange_center=0,total=0,center_total=0;
    for(int y=0;y<f.height;y+=step){for(int x=0;x<f.width;x+=step){
        const auto p=f.pixels[static_cast<size_t>(y)*f.width+x]; const int r=red(p),g=green(p),b=blue(p);
        blue_count += b>130 && b>r*1.22f && b>g*1.08f;
        dark += r<48 && g<48 && b<48; ++total;
        if(x>f.width*.12f&&x<f.width*.88f&&y>f.height*.32f&&y<f.height*.78f){orange_center+=r>175&&g>82&&g<185&&b<92&&r>g*1.12f;++center_total;}
    }}
    return blue_count/static_cast<float>(std::max(1,total))>.045f || dark/static_cast<float>(std::max(1,total))>.58f || orange_center/static_cast<float>(std::max(1,center_total))>.045f;
}

inline std::vector<Component> merge_components(
    std::vector<Component> input,
    int horizontal_gap,
    int vertical_gap
) {
    std::vector<Component> output;
    std::vector<bool> used(input.size(),false);
    for(size_t i=0;i<input.size();++i){
        if(used[i]) continue;
        Component merged=input[i];
        used[i]=true;
        bool changed=true;
        while(changed){changed=false;for(size_t j=0;j<input.size();++j){if(used[j])continue;
            const bool x_close=input[j].left<=merged.right+horizontal_gap&&input[j].right>=merged.left-horizontal_gap;
            const bool y_close=input[j].top<=merged.bottom+vertical_gap&&input[j].bottom>=merged.top-vertical_gap;
            if(x_close&&y_close){merged.left=std::min(merged.left,input[j].left);merged.top=std::min(merged.top,input[j].top);merged.right=std::max(merged.right,input[j].right);merged.bottom=std::max(merged.bottom,input[j].bottom);merged.pixels+=input[j].pixels;used[j]=true;changed=true;}
        }}
        output.push_back(merged);
    }
    return output;
}

}
