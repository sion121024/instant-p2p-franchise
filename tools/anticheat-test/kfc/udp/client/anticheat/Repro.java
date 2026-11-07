package kfc.udp.client.anticheat;
import java.util.*;
public class Repro {
    static long[] CLK={1_000_000_000L};
    static Engine eng(){ Engine e=new Engine(); e.clockNs=()->CLK[0]; AcConfig c=new AcConfig(); c.action=AcConfig.Action.ALERT; e.setConfig(c); return e;}
    static MoveEnv env(boolean fw, boolean jump){ MoveEnv e=new MoveEnv(); e.ticksExisted=1000; e.movementSpeed=0.1; e.hasInput=true; e.forward=fw; e.jumpKey=jump;
        e.groundBelowNew=true; e.groundBelowPrev=true; e.blockFriction=0.6f; return e;}
    static void dump(String n, Engine e, UUID id){ PlayerData p=e.get(id); StringBuilder sb=new StringBuilder(n+": ");
        for (Check c: p.checks().values()) if (c.flags()>0) sb.append(c.id).append(" flags=").append(c.flags()).append(" vl=").append(String.format("%.2f",c.vl)).append("; ");
        System.out.println(sb); }
    public static void main(String[] a){
        // A: 걷다가 반 블록(0.5) 계단 오르기 x5
        { Engine e=eng(); UUID id=UUID.randomUUID(); e.join(id,"a",1);
          double z=0,y=0,vz=0; e.onMove(id,new MoveInput(true,true,0,y,z,0,0,true,false),env(true,false));
          for(int t=0;t<200;t++){ CLK[0]+=50_000_000L; MoveEnv en=env(true,false); vz=vz*0.546+0.098; double dy=0;
            if(t>=40 && t%20==0) dy=0.5; z+=vz; y+=dy; e.onMove(id,new MoveInput(true,false,0,y,z,0,0,true,false),en); e.onClientTickEnd(id);}
          dump("A stairs",e,id);}
        // B: 2칸 높이 터널에서 제자리 점프 반복
        { Engine e=eng(); UUID id=UUID.randomUUID(); e.join(id,"b",1);
          double y=0; e.onMove(id,new MoveInput(true,true,0,0,0,0,0,true,false),env(false,false));
          for(int t=0;t<60;t++){ CLK[0]+=50_000_000L; e.onMove(id,new MoveInput(true,false,0,0,0,0,0,true,false),env(false,false)); e.onClientTickEnd(id);}
          for(int k=0;k<8;k++){
            double[] dys={0.2,-0.0784,-0.1216}; boolean[] ground={false,false,true}; boolean[] ceil={true,false,false};
            for(int i=0;i<3;i++){ CLK[0]+=50_000_000L; MoveEnv en=env(false,i==0); en.groundBelowNew=ground[i]; en.groundBelowPrev=(i==0); en.ceilingAboveNew=ceil[i];
              y+=dys[i]; e.onMove(id,new MoveInput(true,false,0,y,0,0,0,ground[i],false),en); e.onClientTickEnd(id);}
            for(int i=0;i<3;i++){ CLK[0]+=50_000_000L; e.onMove(id,new MoveInput(true,false,0,y,0,0,0,true,false),env(false,false)); e.onClientTickEnd(id);} }
          dump("B tunnel jump",e,id);}
        // C: 텔레포트(5블록) + 핑 동안의 오래된 이동 패킷 3개
        { Engine e=eng(); UUID id=UUID.randomUUID(); e.join(id,"c",1);
          e.onMove(id,new MoveInput(true,true,0,0,0,0,0,true,false),env(false,false));
          for(int t=0;t<60;t++){ CLK[0]+=50_000_000L; e.onMove(id,new MoveInput(true,false,0,0,0,0,0,true,false),env(false,false)); e.onClientTickEnd(id);}
          for(int k=0;k<3;k++){
            e.onTeleport(id,0,0,5*(k+1)); double sz=5*k;
            for(int i=0;i<3;i++){ CLK[0]+=50_000_000L; MoveEnv en=env(false,false); en.awaitingTeleport=true; e.onMove(id,new MoveInput(true,false,0,0,sz,0,0,true,false),en); e.onClientTickEnd(id);}
            for(int i=0;i<20;i++){ CLK[0]+=50_000_000L; e.onMove(id,new MoveInput(true,false,0,0,5*(k+1),0,0,true,false),env(false,false)); e.onClientTickEnd(id);} }
          dump("C teleport",e,id);}
        // D: 서버/네트워크 1.5초 멈춘 뒤 몰려온 틱 끝 패킷
        { Engine e=eng(); UUID id=UUID.randomUUID(); e.join(id,"d",1);
          e.onMove(id,new MoveInput(true,true,0,0,0,0,0,true,false),env(false,false));
          for(int t=0;t<100;t++){ CLK[0]+=50_000_000L; e.onClientTickEnd(id);}
          CLK[0]+=1_500_000_000L; for(int t=0;t<30;t++){ CLK[0]+=1_000_000L; e.onClientTickEnd(id);}
          dump("D lag burst",e,id);}
        for (Engine.Alert al: new ArrayList<Engine.Alert>()) {}
    }
}
