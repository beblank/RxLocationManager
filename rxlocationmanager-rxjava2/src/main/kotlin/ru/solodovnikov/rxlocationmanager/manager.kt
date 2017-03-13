package ru.solodovnikov.rxlocationmanager

import android.annotation.TargetApi
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import io.reactivex.*
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.functions.Function
import java.util.concurrent.TimeoutException

class RxLocationManager internal constructor(private val locationManager: LocationManager,
                                             private val scheduler: Scheduler = AndroidSchedulers.mainThread()) {
    constructor(context: Context) : this(context.getSystemService(Context.LOCATION_SERVICE) as LocationManager)

    /**
     * Get last location from specific provider
     * Observable will emit [ElderLocationException] if [howOldCanBe] is not null and location time is not valid.
     *
     * @param provider provider name
     * @param howOldCanBe how old a location can be
     * @return observable that emit last known location. May emit null
     * @see ElderLocationException
     * @see ProviderHasNoLastLocationException
     */
    @JvmOverloads
    fun getLastLocation(provider: String, howOldCanBe: LocationTime? = null): Single<Location> =
            Single.fromCallable { locationManager.getLastKnownLocation(provider) ?: throw ProviderHasNoLastLocationException(provider) }
                    .compose {
                        if (howOldCanBe == null) it else it.map {
                            if (!isLocationNotOld(it, howOldCanBe)) {
                                throw ElderLocationException(it)
                            }
                            it
                        }
                    }.compose(applySchedulers())

    /**
     * Try to get current location by specific provider.
     * Observable will emit [TimeoutException] in case of timeOut if [timeOut] object is not null.
     * Observable will emit [ProviderDisabledException] if provider is disabled
     *
     * @param provider provider name
     * @param timeOut  optional request timeout
     * @return observable that emit current location
     * @see TimeoutException
     * @see ProviderDisabledException
     */
    @JvmOverloads
    fun requestLocation(provider: String, timeOut: LocationTime? = null): Single<Location> =
            Single.create(RxLocationListener(locationManager, provider))
                    .compose { if (timeOut != null) it.timeout(timeOut.time, timeOut.timeUnit) else it }
                    .compose(applySchedulers())

    private fun isLocationNotOld(location: Location, howOldCanBe: LocationTime) =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                isLocationNotOld17(location, howOldCanBe)
            } else {
                isLocationNotOldDefault(location, howOldCanBe)
            }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    private fun isLocationNotOld17(location: Location, howOldCanBe: LocationTime) =
            SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos < howOldCanBe.timeUnit.toNanos(howOldCanBe.time)

    private fun isLocationNotOldDefault(location: Location, howOldCanBe: LocationTime) =
            System.currentTimeMillis() - location.time < howOldCanBe.timeUnit.toMillis(howOldCanBe.time)

    private fun applySchedulers() = SingleTransformer<Location, Location> { it.subscribeOn(scheduler).observeOn(scheduler) }

    private class RxLocationListener(val locationManager: LocationManager, val provider: String) : SingleOnSubscribe<Location> {

        override fun subscribe(emitter: SingleEmitter<Location>) {
            if (locationManager.isProviderEnabled(provider)) {
                val locationListener = object : LocationListener {
                    override fun onLocationChanged(location: Location?) {
                        emitter.onSuccess(location)
                    }

                    override fun onProviderDisabled(p0: String?) {

                    }

                    override fun onStatusChanged(p0: String?, p1: Int, p2: Bundle?) {

                    }

                    override fun onProviderEnabled(p: String?) {
                        if (provider == p) {
                            emitter.onError(ProviderDisabledException(provider))
                        }
                    }
                }

                locationManager.requestSingleUpdate(provider, locationListener, null)

                emitter.setCancellable { locationManager.removeUpdates(locationListener) }

            } else {
                emitter.onError(ProviderDisabledException(provider))
            }
        }
    }
}

class LocationRequestBuilder internal constructor(private val rxLocationManager: RxLocationManager,
                                                  private val scheduler: Scheduler = AndroidSchedulers.mainThread()) {
    private var defaultLocation: Location? = null
    private var returnDefaultLocationOnError = false
    private val observables: MutableList<Observable<Location>> = arrayListOf()

    constructor(context: Context) : this(RxLocationManager(context))

    /**
     * Try to get current location by specific [provider]
     *
     * @param provider    provider name
     * @param timeOut     optional request timeout
     * @param transformer optional extra transformer
     *
     * @return same builder
     */
    @JvmOverloads
    fun addRequestLocation(provider: String, timeOut: LocationTime? = null,
                           transformer: ObservableTransformer<Location, Location>? = null): LocationRequestBuilder {
        val o = rxLocationManager.requestLocation(provider, timeOut)
                .toObservable()
                .onErrorResumeNext(Function {
                    when (it) {
                        is TimeoutException, is ProviderDisabledException -> Observable.empty<Location>()
                        else -> Observable.error<Location>(it)
                    }
                })

        observables.add(if (transformer != null) o.compose(transformer) else o)

        return this
    }

    /**
     * Get last location from specific [provider]
     *
     * @param provider    provider name
     * @param howOldCanBe optional. How old a location can be
     * @param isNullValid if true, null will be emitted
     * @param transformer optional extra transformer
     * @return same builder
     */
    @JvmOverloads
    fun addLastLocation(provider: String, howOldCanBe: LocationTime? = null,
                        transformer: ObservableTransformer<Location, Location>? = null): LocationRequestBuilder {
        val o = rxLocationManager.getLastLocation(provider, howOldCanBe)
                .toObservable()
                .onErrorResumeNext(Function {
                    when (it) {
                        is ElderLocationException, is ProviderDisabledException, is ProviderHasNoLastLocationException -> Observable.empty<Location>()
                        else -> Observable.error<Location>(it)
                    }
                })

        observables.add(if (transformer != null) o.compose(transformer) else o)

        return this
    }

    /**
     * Set location that will be returned in case of empty observable
     *
     * @param defaultLocation default location
     * @return same builder
     */
    fun setDefaultLocation(defaultLocation: Location?): LocationRequestBuilder {
        this.defaultLocation = defaultLocation
        return this
    }

    /**
     * If [returnDefaultLocationOnError] is true, result observable will emit default location if any exception occur
     *
     * @param returnDefaultLocationOnError emit default location if any exception occur?
     * @return same builder
     */
    fun setReturnDefaultLocationOnError(returnDefaultLocationOnError: Boolean): LocationRequestBuilder {
        this.returnDefaultLocationOnError = returnDefaultLocationOnError
        return this
    }

    fun create(): Maybe<Location> {

        var result = Observable.empty<Location>()

        observables.forEach {
            result = result.concatWith(it.compose {
                it.onErrorResumeNext(Function {
                    if (returnDefaultLocationOnError)
                        Observable.empty<Location>()
                    else
                        Observable.error<Location>(it)
                })
            })
        }

        return result.firstElement()
                .compose { if (defaultLocation != null) it.defaultIfEmpty(defaultLocation) else it }
                .observeOn(scheduler)
    }
}

