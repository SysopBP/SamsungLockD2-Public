/* D2 root worker; NR mode pairing adapted from OneUIX FiveGTileService.
 * SPDX-License-Identifier: AGPL-3.0-only */
package app.d2lock.root;
import android.content.Context;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
public final class FiveGWorker {
 public static void main(String[] args) {
  try {
   if(android.os.Process.myUid()!=0) throw new SecurityException("Root worker required");
   if(android.os.Looper.myLooper()==null) android.os.Looper.prepareMainLooper();
   Class<?> at=Class.forName("android.app.ActivityThread");
   Object thread=at.getMethod("systemMain").invoke(null);
   Context c=(Context)at.getMethod("getSystemContext").invoke(thread);
   int data=SubscriptionManager.getActiveDataSubscriptionId();
   if(data<0) throw new IllegalStateException("No active data subscription");
   TelephonyManager base=c.getSystemService(TelephonyManager.class),tm=base.createForSubscriptionId(data);
   java.lang.reflect.Method read=TelephonyManager.class.getMethod("getAllowedNetworkTypesForReason",int.class);
   java.lang.reflect.Method write=TelephonyManager.class.getMethod("setAllowedNetworkTypesForReason",int.class,long.class);
   long nr=TelephonyManager.NETWORK_TYPE_BITMASK_NR,mask=((Number)read.invoke(tm,0)).longValue();
   boolean enabled=(mask&nr)!=0;
   if(args.length==1 && "toggle".equals(args[0])) {
    long next=enabled ? mask&~nr : mask|nr;write.invoke(tm,0,next);
    if(((Number)read.invoke(tm,0)).longValue()!=next) throw new IllegalStateException("Network type read-back failed");
    int[][] pairs={{8,25},{9,26},{10,27},{11,24},{12,28},{15,29},{17,30},{19,31},{20,32},{22,33}};
    String key="preferred_network_mode"+data;int current=Settings.Global.getInt(c.getContentResolver(),key,-1);
    for(int[] pair:pairs) if(current==pair[enabled?1:0]) { Settings.Global.putInt(c.getContentResolver(),key,pair[enabled?0:1]);break; }
    enabled=!enabled;
   } else if(args.length!=1 || !"status".equals(args[0])) throw new IllegalArgumentException("Use status or toggle");
   System.out.println("NR="+(enabled?1:0));
  } catch(Throwable e) { System.err.println("D2_5G_UNAVAILABLE "+e);System.exit(1); }
 }
}
