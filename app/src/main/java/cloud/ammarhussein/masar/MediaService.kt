package cloud.ammarhussein.masar

import android.service.notification.NotificationListenerService

/**
 * Android only tells an app what is playing if the app has "notification
 * access", and that access is granted to a service like this one. Masar never
 * reads the notifications themselves; holding the access is the whole job.
 */
class MediaService : NotificationListenerService()
