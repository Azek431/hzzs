#include "BambooVisionEngine.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <deque>
#include <limits>
#include <numeric>
#include <vector>

namespace {
constexpr int kProtocolVersion = 4;
constexpr int kMaxObjects = 48;

struct Pixel { int r,g,b; };
struct Rect { int x=0,y=0,w=0,h=0,area=0; };
struct View {
    const uint8_t* data=nullptr; int w=0,h=0,stride=0;
    Pixel at(int x,int y) const {
        const uint8_t* p=data + y*stride + x*3;
        return {p[0],p[1],p[2]};
    }
};

inline int clampi(int v,int lo,int hi){ return std::max(lo,std::min(v,hi)); }
inline float clampf(float v,float lo,float hi){ return std::max(lo,std::min(v,hi)); }
inline int max3(int a,int b,int c){ return std::max(a,std::max(b,c)); }
inline int min3(int a,int b,int c){ return std::min(a,std::min(b,c)); }

struct Mask {
    int w=0,h=0;
    std::vector<uint8_t> d;
    Mask()=default;
    Mask(int W,int H):w(W),h(H),d(static_cast<size_t>(W)*H,0){}
    uint8_t& at(int x,int y){ return d[static_cast<size_t>(y)*w+x]; }
    uint8_t at(int x,int y) const { return d[static_cast<size_t>(y)*w+x]; }
};

Mask dilate3(const Mask& src,int iterations){
    Mask a=src,b(src.w,src.h);
    for(int it=0;it<iterations;++it){
        std::fill(b.d.begin(),b.d.end(),0);
        for(int y=1;y<src.h-1;++y) for(int x=1;x<src.w-1;++x){
            uint8_t v=0;
            for(int dy=-1;dy<=1 && !v;++dy) for(int dx=-1;dx<=1;++dx) if(a.at(x+dx,y+dy)){v=1;break;}
            b.at(x,y)=v;
        }
        a.d.swap(b.d);
    }
    return a;
}
Mask erode3(const Mask& src,int iterations){
    Mask a=src,b(src.w,src.h);
    for(int it=0;it<iterations;++it){
        std::fill(b.d.begin(),b.d.end(),0);
        for(int y=1;y<src.h-1;++y) for(int x=1;x<src.w-1;++x){
            uint8_t v=1;
            for(int dy=-1;dy<=1 && v;++dy) for(int dx=-1;dx<=1;++dx) if(!a.at(x+dx,y+dy)){v=0;break;}
            b.at(x,y)=v;
        }
        a.d.swap(b.d);
    }
    return a;
}
Mask closeOpen(const Mask& src,int closeIts,int openIts){
    Mask out=src;
    if(closeIts>0) out=erode3(dilate3(out,closeIts),closeIts);
    if(openIts>0) out=dilate3(erode3(out,openIts),openIts);
    return out;
}

std::vector<Rect> components(const Mask& m,int minArea,int x0,int y0,int x1,int y1){
    x0=clampi(x0,0,m.w);x1=clampi(x1,0,m.w);y0=clampi(y0,0,m.h);y1=clampi(y1,0,m.h);
    std::vector<uint8_t> seen(static_cast<size_t>(m.w)*m.h,0);
    std::vector<Rect> out;
    std::vector<int> q; q.reserve(4096);
    for(int sy=y0;sy<y1;++sy) for(int sx=x0;sx<x1;++sx){
        size_t sidx=static_cast<size_t>(sy)*m.w+sx;
        if(!m.d[sidx]||seen[sidx]) continue;
        q.clear(); q.push_back(static_cast<int>(sidx));seen[sidx]=1;
        int minx=sx,maxx=sx,miny=sy,maxy=sy,area=0;
        for(size_t qi=0;qi<q.size();++qi){
            int idx=q[qi];int y=idx/m.w,x=idx-y*m.w;++area;
            minx=std::min(minx,x);maxx=std::max(maxx,x);miny=std::min(miny,y);maxy=std::max(maxy,y);
            for(int dy=-1;dy<=1;++dy) for(int dx=-1;dx<=1;++dx){
                if(!dx&&!dy) continue;
                const int nx=x+dx,ny=y+dy;
                if(nx<x0||nx>=x1||ny<y0||ny>=y1)continue;
                size_t ni=static_cast<size_t>(ny)*m.w+nx;
                if(m.d[ni]&&!seen[ni]){seen[ni]=1;q.push_back(static_cast<int>(ni));}
            }
        }
        if(area>=minArea)out.push_back({minx,miny,maxx-minx+1,maxy-miny+1,area});
    }
    return out;
}

void addObject(HzzsFrameResult& r,int kind,int app,int sz,const Rect& b,float conf){
    if(r.object_count>=kMaxObjects)return;
    auto& o=r.objects[r.object_count++];
    o.kind=kind;o.appearance=app;o.size_class=sz;o.x=b.x;o.y=b.y;o.width=b.w;o.height=b.h;o.confidence=clampf(conf,0.f,1.f);
}

struct PlayerInfo { Rect box; float confidence=0.f; };
PlayerInfo detectPlayer(const View& v){
    Mask m(v.w,v.h);
    int x1=static_cast<int>(v.w*.47f), y0=static_cast<int>(v.h*.32f), y1=static_cast<int>(v.h*.76f);
    for(int y=y0;y<y1;++y) for(int x=0;x<x1;++x){
        auto p=v.at(x,y);
        // The avatar's cap/body is red-magenta: blue stays above green.  This relative
        // condition rejects both the yellow bamboo background and the pale pink sky.
        const bool magenta=p.r>145&&p.r-p.g>35&&p.b-p.g>3&&p.r-p.b>0;
        const bool deepRed=p.r>165&&p.r-p.g>55&&p.g<130&&p.b<145;
        m.at(x,y)=static_cast<uint8_t>(magenta||deepRed);
    }
    m=closeOpen(m,1,1);
    auto cs=components(m,std::max(8,v.w*v.h/20000),0,y0,x1,y1);
    PlayerInfo best;
    float bestScore=-1.f;
    for(const auto& c:cs){
        float wr=float(c.w)/v.w,hr=float(c.h)/v.h,cx=float(c.x+c.w*.5f)/v.w;
        if(wr<.035f||wr>.23f||hr<.025f||hr>.18f)continue;
        float fill=float(c.area)/(c.w*c.h);
        float pos=1.f-std::min(1.f,std::abs(cx-.22f)/.24f);
        float score=c.area*(.65f+.35f*pos)*(0.5f+fill);
        if(score>bestScore){bestScore=score;best.box=c;best.confidence=clampf(.45f+fill*.8f,0.f,1.f);}
    }
    return best;
}

struct SeasonInfo { int season=HZZS_SEASON_UNKNOWN; float confidence=0.f; float greenRatio=0.f,pinkRatio=0.f; };
struct FloorInfo { int y=0;float confidence=0.f;float support=0.f; };
float supportRatioAt(const View& v,int season,int y){
    y=clampi(y,0,v.h-1);int hit=0,n=0;
    for(int x=0;x<v.w;x+=2){
        // Skip the player's usual body area, but keep both sides for wide gaps.
        if(x>int(v.w*.15f)&&x<int(v.w*.31f))continue;
        auto p=v.at(x,y);int mx=max3(p.r,p.g,p.b),mn=min3(p.r,p.g,p.b);++n;
        if(season==HZZS_SEASON_BAMBOO_STUDY){if(p.g>62&&p.g-p.r>4&&p.g-p.b>18)++hit;}
        else {if(p.r>200&&p.g>178&&p.b>155&&mx-mn<72)++hit;}
    }
    return n?float(hit)/n:0.f;
}
FloorInfo detectFloor(const View& v,int season){
    int lo=int(v.h*.52f),hi=int(v.h*.675f);
    float best=-1e9f;int bestY=int(v.h*.60f);float bestSupport=0.f;
    const float prior=.60f*v.h;
    for(int y=lo;y<hi;++y){
        long edge=0;int samples=0;
        for(int x=0;x<v.w;x+=2){
            if(x>int(v.w*.15f)&&x<int(v.w*.31f))continue;
            auto a=v.at(x,y),b=v.at(x,std::min(v.h-1,y+1));
            edge+=std::abs(a.r-b.r)+std::abs(a.g-b.g)+std::abs(a.b-b.b);++samples;
        }
        float edgeMean=samples?float(edge)/(samples*3):0.f;
        float sup=supportRatioAt(v,season,y);
        float supAbove=supportRatioAt(v,season,std::max(0,y-std::max(3,int(v.h*.008f))));
        float transition=std::max(0.f,sup-supAbove);
        float score=edgeMean*.78f+sup*14.f+transition*34.f-std::abs(y-prior)/(v.h*.075f)*7.f;
        if(score>best){best=score;bestY=y;bestSupport=sup;}
    }
    FloorInfo f;f.y=bestY;f.support=bestSupport;f.confidence=clampf((best-4.f)/22.f,0.f,1.f);return f;
}

bool overlapsX(const Rect& a,const Rect& b){return std::max(a.x,b.x)<std::min(a.x+a.w,b.x+b.w);}

void detectBamboo(const View& v,int floor,HzzsFrameResult& out){
    Mask stone(v.w,v.h),white(v.w,v.h),panda(v.w,v.h),orb(v.w,v.h);
    int xMin=0,yMin=int(v.h*.18f),yMax=clampi(floor+int(v.h*.03f),0,v.h);
    for(int y=yMin;y<yMax;++y)for(int x=xMin;x<v.w;++x){
        auto p=v.at(x,y);int mx=max3(p.r,p.g,p.b),mn=min3(p.r,p.g,p.b),range=mx-mn;
        if(y>=int(v.h*.31f)&&mx>42&&mx<210&&range<62&&p.b>=p.r-22)stone.at(x,y)=1;
        if(mx>190&&range<60)white.at(x,y)=1;
        // expanded white/black face seeds; dilation later merges ears and face.
        bool pw=(mx>205&&range<45);bool pb=(mx<80&&range<45);
        panda.at(x,y)=static_cast<uint8_t>(pw||pb);
        bool blue=(p.b>145&&p.b-p.r>18&&p.b-p.g>5);bool orange=(p.r>180&&p.g>100&&p.b<100);bool mag=(p.r>175&&p.b>110&&p.r-p.g>15);
        orb.at(x,y)=static_cast<uint8_t>(blue||orange||mag);
    }
    stone=closeOpen(stone,3,1);std::vector<Rect> groundBoxes;
    for(const auto& c:components(stone,std::max(5,v.w*v.h/8000),xMin,int(v.h*.31f),v.w,yMax)){
        float wr=float(c.w)/v.w,hr=float(c.h)/v.h,fill=float(c.area)/(c.w*c.h);
        const bool edgePartial=c.x<=int(v.w*.018f)||c.x+c.w>=v.w-int(v.w*.018f);
        const float minWr=edgePartial?.012f:.032f;
        const float minHr=edgePartial?.035f:.085f;
        // Real panda statues are tall, floor-connected bodies (about 0.12H or 0.18H in
        // the supplied seasons).  The multicolour shield/power-up false positive is only
        // about 0.064H, so height is a more stable discriminator than absolute colour.
        if(wr<minWr||wr>.29f||hr<minHr||hr>.28f||c.y+c.h<floor-int(v.h*.06f)||fill<.10f)continue;
        int sz=hr>.125f?HZZS_SIZE_LARGE:HZZS_SIZE_SMALL;
        Rect b=c;b.x=std::max(0,b.x-2);b.y=std::max(0,b.y-2);b.w=std::min(v.w-b.x,b.w+4);b.h=std::min(v.h-b.y,b.h+4);
        addObject(out,HZZS_OBJECT_GROUND,HZZS_APPEARANCE_PANDA_STATUE,sz,b,.54f+std::min(.4f,fill));groundBoxes.push_back(b);
    }
    // Brush: dark pointed tip near the floor, white bristles above it and brown handle above the bristles.
    Mask tipMask(v.w,v.h);
    for(int y=int(v.h*.34f);y<std::min(v.h,floor+int(v.h*.02f));++y)for(int x=int(v.w*.16f);x<v.w;++x){
        auto p=v.at(x,y);int mx=max3(p.r,p.g,p.b),mn=min3(p.r,p.g,p.b);
        if(mx<160&&mn<105&&mx-mn<105)tipMask.at(x,y)=1;
    }
    tipMask=closeOpen(tipMask,1,1);
    for(const auto& tip:components(tipMask,std::max(3,v.w*v.h/60000),int(v.w*.16f),int(v.h*.34f),v.w,std::min(v.h,floor+int(v.h*.02f)))){
        float wr=float(tip.w)/v.w,hr=float(tip.h)/v.h;
        const int floorGap=floor-(tip.y+tip.h);
        // The black brush tip is much larger than a panda-token facial feature and floats
        // 4-12% H above the floor. This is the strongest season-specific discriminator.
        if(wr<.055f||wr>.175f||hr<.038f||hr>.120f)continue;
        if(float(tip.h)/std::max(1,tip.w)<.80f||float(tip.h)/std::max(1,tip.w)>2.30f)continue;
        if(floorGap<int(v.h*.035f)||floorGap>int(v.h*.125f))continue;
        int cx=tip.x+tip.w/2,rx=std::max(int(v.w*.075f),tip.w);
        int x0=clampi(cx-rx,0,v.w),x1=clampi(cx+rx,0,v.w);
        int wy0=clampi(tip.y-int(v.h*.105f),int(v.h*.20f),v.h),wy1=tip.y;
        int by0=clampi(wy0-int(v.h*.20f),int(v.h*.14f),v.h),by1=wy0;
        int whiteN=0,wn=0,brownN=0,bn=0;
        for(int y=wy0;y<wy1;y+=2)for(int x=x0;x<x1;x+=2){auto p=v.at(x,y);int mx=max3(p.r,p.g,p.b),mn=min3(p.r,p.g,p.b);++wn;if(mx>185&&mx-mn<72)++whiteN;}
        for(int y=by0;y<by1;y+=2)for(int x=x0;x<x1;x+=2){auto p=v.at(x,y);++bn;if(p.r>62&&p.r>p.g+4&&p.g>p.b+1&&p.r<205)++brownN;}
        float whiteR=wn?float(whiteN)/wn:0.f,brownR=bn?float(brownN)/bn:0.f;
        if(whiteR<.24f||brownR<.075f)continue;
        Rect box{x0,by0,x1-x0,tip.y+tip.h-by0,tip.area};
        addObject(out,HZZS_OBJECT_OVERHEAD,HZZS_APPEARANCE_BRUSH,HZZS_SIZE_HANGING,box,.76f+std::min(.20f,whiteR));
    }
    // Gap: loss of green bamboo support + dark void below.
    std::vector<uint8_t> support(v.w,0);int by0=clampi(floor-int(v.h*.02f),0,v.h),by1=clampi(floor+int(v.h*.02f),0,v.h);
    for(int x=0;x<v.w;++x){int hit=0,n=0;for(int y=by0;y<by1;++y){auto p=v.at(x,y);++n;if(p.g>62&&p.g-p.r>4&&p.g-p.b>18)++hit;}support[x]=n&&float(hit)/n>.22f;}
    int smooth=std::max(3,int(v.w*.016f));std::vector<uint8_t> sup=support;
    for(int x=0;x<v.w;++x){int hit=0,n=0;for(int k=-smooth;k<=smooth;++k){int xx=x+k;if(xx>=0&&xx<v.w){++n;hit+=support[xx];}}sup[x]=hit>=std::max(1,n/3);}
    int start=int(v.w*.23f),end=int(v.w*.992f);
    for(int i=start;i<end;){if(sup[i]){++i;continue;}int j=i;while(j<end&&!sup[j])++j;int width=j-i;
        if(width>int(v.w*.030f)){
            int dy0=clampi(floor+int(v.h*.02f),0,v.h),dy1=clampi(floor+int(v.h*.23f),0,v.h),dark=0,n=0;
            for(int y=dy0;y<dy1;y+=3)for(int x=i;x<j;x+=3){auto p=v.at(x,y);++n;if(max3(p.r,p.g,p.b)<108)++dark;}
            auto meanRange=[&](int a,int b){a=clampi(a,0,v.w);b=clampi(b,0,v.w);int nn=std::max(1,b-a),s=0;for(int x=a;x<b;++x)s+=sup[x];return float(s)/nn;};
            float dr=n?float(dark)/n:0.f,ls=meanRange(i-int(v.w*.08f),i),rs=meanRange(j,j+int(v.w*.08f));
            Rect cand{i,by0,width,int(v.h*.22f),width};
            bool blocked=false;for(const auto& g:groundBoxes)if(overlapsX(cand,g)){blocked=true;break;}
            const bool normalGap=width>int(v.w*.112f)&&ls>.22f&&(rs>.22f||j>=end-int(v.w*.03f));
            const bool enteredGap=i<=start+int(v.w*.035f)&&rs>.22f&&width>int(v.w*.12f);
            const bool leftEdgeGap=i<=start+int(v.w*.010f)&&rs>.40f&&width>int(v.w*.055f)&&dr>.50f;
            const bool strongGap=width>int(v.w*.30f)&&dr>.70f;
            if((!blocked||strongGap)&&dr>.16f&&(normalGap||enteredGap||leftEdgeGap||strongGap)){int sz=width>int(v.w*.34f)?HZZS_SIZE_WIDE:HZZS_SIZE_NARROW;addObject(out,HZZS_OBJECT_GAP,HZZS_APPEARANCE_BAMBOO_GAP,sz,cand,.66f+std::min(.3f,dr));}
        }i=std::max(j,i+1);
    }
    // A gap can already be almost completely behind the player.  Scan the extreme left
    // edge separately so it remains visible in diagnostics without weakening the forward
    // gap threshold used for automatic actions.
    {
        const int lx1=std::max(1,int(v.w*.12f)), lx2=std::max(lx1+1,int(v.w*.40f));
        int leftSup=0,rightSup=0;
        for(int x=0;x<lx1;++x)leftSup+=sup[x];
        for(int x=lx1;x<lx2;++x)rightSup+=sup[x];
        const float lsr=float(leftSup)/lx1,rsr=float(rightSup)/std::max(1,lx2-lx1);
        int dark=0,n=0;const int dy0=clampi(floor+int(v.h*.02f),0,v.h),dy1=clampi(floor+int(v.h*.23f),0,v.h);
        for(int y=dy0;y<dy1;y+=3)for(int x=0;x<lx1;x+=3){auto p=v.at(x,y);++n;if(max3(p.r,p.g,p.b)<108)++dark;}
        const float dr=n?float(dark)/n:0.f;
        if(lsr<.16f&&rsr>.42f&&dr>.38f){
            Rect edge{0,by0,lx1,int(v.h*.22f),lx1};
            addObject(out,HZZS_OBJECT_GAP,HZZS_APPEARANCE_BAMBOO_GAP,HZZS_SIZE_NARROW,edge,.64f+std::min(.25f,dr));
        }
    }

    // Fallback for the season's large wooden crate / open floor discontinuity.
    // Some views contain a dark crate spanning most of the lower playfield while the
    // green floor support is partly hidden by perspective or UI effects.  Detecting a
    // broad, vertically persistent dark region below the floor is both cheaper and more
    // stable than weakening the normal support-loss rule.  The 25% W minimum keeps panda
    // statues, furniture legs and token shadows out of this path.
    {
        const int dy0=clampi(floor+int(v.h*.02f),0,v.h);
        const int dy1=clampi(floor+int(v.h*.23f),dy0+1,v.h);
        std::vector<float> darkColumn(v.w,0.f);
        for(int x=0;x<v.w;++x){
            int dark=0,n=0;
            for(int y=dy0;y<dy1;y+=2){++n;auto p=v.at(x,y);if(max3(p.r,p.g,p.b)<108)++dark;}
            darkColumn[x]=n?float(dark)/n:0.f;
        }
        const int radius=std::max(2,int(v.w*.006f));
        std::vector<uint8_t> active(v.w,0);
        for(int x=0;x<v.w;++x){
            float sum=0.f;int n=0;
            for(int k=-radius;k<=radius;++k){int xx=x+k;if(xx>=0&&xx<v.w){sum+=darkColumn[xx];++n;}}
            active[x]=static_cast<uint8_t>(n&&sum/n>.70f);
        }
        for(int i=0;i<v.w;){
            if(!active[i]){++i;continue;}
            int j=i;while(j<v.w&&active[j])++j;
            const int width=j-i;
            if(width>int(v.w*.25f)){
                float mean=0.f;for(int x=i;x<j;++x)mean+=darkColumn[x];mean/=std::max(1,width);
                Rect box{i,dy0,width,dy1-dy0,width};
                const int sz=width>int(v.w*.34f)?HZZS_SIZE_WIDE:HZZS_SIZE_NARROW;
                addObject(out,HZZS_OBJECT_GAP,HZZS_APPEARANCE_BAMBOO_GAP,sz,box,.72f+std::min(.25f,mean*.25f));
            }
            i=std::max(j,i+1);
        }
    }

    // Panda tokens: merge black/white face features; require white and dark mixture and floating position.
    panda=dilate3(panda,2);panda=closeOpen(panda,1,1);
    for(const auto& c:components(panda,std::max(4,v.w*v.h/45000),int(v.w*.08f),int(v.h*.25f),v.w,std::min(v.h,floor+int(v.h*.02f)))){
        float wr=float(c.w)/v.w,hr=float(c.h)/v.h,ar=float(c.w)/std::max(1,c.h);
        if(wr<.025f||wr>.15f||hr<.018f||hr>.11f||ar<.55f||ar>2.3f)continue;
        int whiteN=0,darkN=0,n=0;
        for(int y=c.y;y<c.y+c.h;++y)for(int x=c.x;x<c.x+c.w;++x){auto p=v.at(x,y);int mx=max3(p.r,p.g,p.b),mn=min3(p.r,p.g,p.b);++n;if(mx>205&&mx-mn<50)++whiteN;if(mx<85&&mx-mn<50)++darkN;}
        float wrat=n?float(whiteN)/n:0.f,drat=n?float(darkN)/n:0.f;
        bool groundLike=c.y+c.h>floor-int(v.h*.03f)&&c.h>int(v.h*.06f);
        if(!groundLike&&wrat>.08f&&drat>.018f)addObject(out,HZZS_OBJECT_COLLECTIBLE,HZZS_APPEARANCE_PANDA_TOKEN,HZZS_SIZE_SMALL,c,.55f+std::min(.35f,wrat+drat));
    }
    orb=closeOpen(orb,1,1);
    for(const auto& c:components(orb,std::max(4,v.w*v.h/50000),int(v.w*.10f),int(v.h*.28f),v.w,std::min(v.h,floor+int(v.h*.03f)))){
        float wr=float(c.w)/v.w,hr=float(c.h)/v.h,ar=float(c.w)/std::max(1,c.h),fill=float(c.area)/(c.w*c.h);
        if(wr<.018f||wr>.15f||hr<.014f||hr>.11f||ar<.45f||ar>2.4f||fill<.06f)continue;
        addObject(out,HZZS_OBJECT_POWERUP,HZZS_APPEARANCE_BONUS_ORB,HZZS_SIZE_SMALL,c,.45f+std::min(.35f,fill));
    }
}


float rectIou(const HzzsObject& a,const HzzsObject& b){
    int x0=std::max(a.x,b.x),y0=std::max(a.y,b.y),x1=std::min(a.x+a.width,b.x+b.width),y1=std::min(a.y+a.height,b.y+b.height);
    int inter=std::max(0,x1-x0)*std::max(0,y1-y0);int uni=a.width*a.height+b.width*b.height-inter;
    return uni>0?float(inter)/uni:0.f;
}
void postprocess(HzzsFrameResult& r){
    std::vector<int> order(r.object_count);std::iota(order.begin(),order.end(),0);
    std::sort(order.begin(),order.end(),[&](int a,int b){return r.objects[a].confidence>r.objects[b].confidence;});
    HzzsObject kept[kMaxObjects];int n=0;
    for(int idx:order){const auto& o=r.objects[idx];bool dup=false;
        for(int j=0;j<n;++j){
            if(kept[j].appearance!=o.appearance)continue;
            int acx=o.x+o.width/2,bcx=kept[j].x+kept[j].width/2;
            const int gap=std::max(0,std::max(o.x,kept[j].x)-std::min(o.x+o.width,kept[j].x+kept[j].width));
            const bool sameGap=o.kind==HZZS_OBJECT_GAP&&gap<std::max(12,std::min(o.width,kept[j].width)/2);
            if(sameGap||rectIou(o,kept[j])>.18f||std::abs(acx-bcx)<std::max(o.width,kept[j].width)/2){dup=true;break;}
        }
        if(!dup&&n<kMaxObjects)kept[n++]=o;
    }
    std::copy(kept,kept+n,r.objects);r.object_count=n;
}

} // namespace

extern "C" int32_t hzzs_bamboo_analyze_rgb_internal(const uint8_t* rgb,int32_t width,int32_t height,int32_t stride,HzzsFrameResult* out){
    if(!rgb||!out||width<32||height<64||stride<width*3)return -1;
    std::memset(out,0,sizeof(*out));out->protocol_version=kProtocolVersion;View v{rgb,width,height,stride};
    SeasonInfo season; season.season=HZZS_SEASON_BAMBOO_STUDY; season.confidence=1.0f; auto player=detectPlayer(v);auto floor=detectFloor(v,season.season);
    out->season=season.season;out->season_confidence=season.confidence;out->floor_y=floor.y;out->floor_confidence=floor.confidence;
    out->player_x=player.box.x;out->player_y=player.box.y;out->player_width=player.box.w;out->player_height=player.box.h;out->player_confidence=player.confidence;
    bool plausibleFloor=float(floor.y)/height>.47f&&float(floor.y)/height<.73f;
    bool running=season.season!=HZZS_SEASON_UNKNOWN&&plausibleFloor&&floor.support>.055f&&floor.confidence>.05f;
    out->scene_state=running?HZZS_SCENE_RUNNING:HZZS_SCENE_UNSAFE;
    if(!running)return 0;
    detectBamboo(v,floor.y,*out);
    postprocess(*out);
    return 0;
}
extern "C" const char* hzzs_bamboo_engine_version(){return "bamboo-study-1.0";}
