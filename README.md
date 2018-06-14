# Argon Object Classifier

Computer vision object classifier for Android using the [Cobalt Neural Network](https://github.com/cnvx/cobalt).

## About

This app is capable of recognising 100 types of objects (taken from the [CIFAR-100 data set](https://www.cs.toronto.edu/~kriz/cifar.html)):

*apple, aquarium fish, baby, bear, beaver, bed, bee, beetle, bicycle, bottle, bowl, boy, bridge, bus, butterfly, camel, can, castle, caterpillar, cattle, chair, chimpanzee, clock, cloud, cockroach, couch, crab, crocodile, cup, dinosaur, dolphin, elephant, flatfish, forest, fox, girl, hamster, house, kangaroo, keyboard, lamp, lawn mower, leopard, lion, lizard, lobster, man, maple tree, motorcycle, mountain, mouse, mushroom, oak tree, orange, orchid, otter, palm tree, pear, pickup truck, pine tree, plain, plate, poppy, porcupine, possum, rabbit, raccoon, ray, road, rocket, rose, sea, seal, shark, shrew, skunk, skyscraper, snail, snake, spider, squirrel, streetcar, sunflower, sweet pepper, table, tank, telephone, television, tiger, tractor, train, trout, tulip, turtle, wardrobe, whale, willow tree, wolf, woman, worm*

It would be relatively easy to retrain the neural network with more object classes, provided you have enough training data.

## Install

### Just the app

Enable installation of apps from unknown sources:
1. Settings -> Security
2. Enable *Unknown sources*

Then download and install [this APK](https://github.com/cnvx/argon/raw/master/argon.apk).

### Build it yourself

Clone or download this repository and perform a Gradle sync, this can be done from Android Studio:
1. Build -> Make Project
2. File -> Sync Project with Gradle Files

Now you should be able to build and run the app.

## License

This app uses the [MIT License](LICENSE).  
Code belonging to the Android Open Source Project is released under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0).
