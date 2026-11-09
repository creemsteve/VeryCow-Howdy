package chat.vch.mobile;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/** Required visible lifetime for MediaProjection; the call owns and closes the capture. */
public final class VideoCaptureService extends Service {
    static volatile VideoCapture capture;
    public IBinder onBind(Intent intent){return null;}
    public int onStartCommand(Intent intent,int flags,int startId){
        NotificationManager notifications=getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("screen-share","Screen sharing",NotificationManager.IMPORTANCE_LOW));
        Intent stop=new Intent(this,VideoCaptureService.class).setAction("stop");
        Notification notification=new Notification.Builder(this,"screen-share").setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("VCH screen sharing").setContentText("Your screen is shared with the voice call.").setOngoing(true)
            .addAction(new Notification.Action.Builder(null,"Stop sharing",PendingIntent.getService(this,0,stop,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT)).build()).build();
        startForeground(73,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        VideoCapture current=capture;
        if(current==null||intent==null||"stop".equals(intent.getAction())){if(current!=null)current.stop("screen");stopSelf();return START_NOT_STICKY;}
        try{current.screen(intent.getIntExtra("result",Activity.RESULT_CANCELED),intent.getParcelableExtra("data"),intent.getIntExtra("kbps",500));}
        catch(Exception e){current.stop("screen");stopSelf();}
        return START_NOT_STICKY;
    }
    public void onDestroy(){VideoCapture current=capture;if(current!=null)current.stop("screen");super.onDestroy();}
}
