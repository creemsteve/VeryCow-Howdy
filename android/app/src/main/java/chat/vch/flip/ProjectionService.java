package chat.vch.flip;

import android.app.*;
import android.content.*;
import android.os.IBinder;
import android.content.pm.ServiceInfo;

/** Android 14 requires a foreground service before consuming projection consent. */
public final class ProjectionService extends Service {
    private static VideoEngine pending;
    private VideoEngine active;
    static synchronized void begin(Context context,VideoEngine engine,int result,Intent data){pending=engine;context.startForegroundService(new Intent(context,ProjectionService.class).putExtra("result",result).putExtra("consent",data));}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        NotificationManager manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("vch_screen","VCH screen sharing",NotificationManager.IMPORTANCE_LOW));
        Intent stop=new Intent(this,ProjectionService.class).setAction("stop");PendingIntent action=PendingIntent.getService(this,1,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,"vch_screen").setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("VCH is sharing your screen").addAction(new Notification.Action.Builder(null,"Stop sharing",action).build()).setOngoing(true).build();
        startForeground(202,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        if(intent==null||"stop".equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        synchronized(ProjectionService.class){active=pending;pending=null;}
        if(active==null){stopSelf();return START_NOT_STICKY;}
        active.screen(this,intent.getIntExtra("result",0),intent.getParcelableExtra("consent"));return START_NOT_STICKY;
    }
    @Override public void onDestroy(){if(active!=null)active.stopScreen();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
